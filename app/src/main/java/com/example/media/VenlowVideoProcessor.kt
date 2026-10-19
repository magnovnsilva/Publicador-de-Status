package com.example.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.model.AspectFitMode
import com.example.model.EncodedOutputVideo
import com.example.model.EncodingConfig
import com.example.model.SharpenLevel
import com.example.model.VideoMetadata
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Venlow-Style HD Video Transcoding Engine powered by Jetpack Media3 Transformer + Hardware H.264.
 *
 * Implements the exact pipeline from the user's StatusHD specification:
 * 1. Copies selected video URI to internal cache (`input_temp.mp4`)
 * 2. Applies vertical 1080x1920 scaling + black padding (`LAYOUT_SCALE_TO_FIT`) or crop (`LAYOUT_SCALE_TO_FIT_WITH_CROP`),
 *    3x3 Gaussian Unsharp Mask (`UnsharpGlEffect`), 30fps target, and H.264 High Profile @ Level 4.0 at 3200 kbps + AAC 128k.
 * 3. Saves the optimized output to `Movies/StatusHD/StatusHD_<timestamp>.mp4` in Android MediaStore.
 */
class VenlowVideoProcessor(private val context: Context) {

    suspend fun extractMetadata(uri: Uri): VideoMetadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var width = 1080
        var height = 1920
        var durationMs = 0L
        var bitrateBps = 0L
        var rotation = 0
        var frameRate = 30f
        var mimeType = "video/mp4"

        try {
            retriever.setDataSource(context, uri)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val fpsMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                if (fpsMeta != null && fpsMeta > 0f) {
                    frameRate = fpsMeta
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        var displayName = "video_selecionado.mp4"
        var sizeBytes = 0L

        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx != -1) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                    if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }
        } else if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.exists()) {
                displayName = f.name
                sizeBytes = f.length()
            }
        }

        if (sizeBytes == 0L) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0) sizeBytes = afd.length
                }
            } catch (_: Exception) {
            }
        }

        VideoMetadata(
            uri = uri,
            displayName = displayName,
            width = width,
            height = height,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            frameRate = frameRate,
            bitrateBps = bitrateBps,
            rotationDegrees = rotation,
            mimeType = mimeType
        )
    }

    @OptIn(UnstableApi::class)
    fun buildVideoEffects(config: EncodingConfig): List<Effect> {
        val effects = mutableListOf<Effect>()
        val layoutMode = when (config.aspectFitMode) {
            AspectFitMode.PAD_BLACK -> Presentation.LAYOUT_SCALE_TO_FIT
            AspectFitMode.CROP_FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        }

        // Equivalent to FFmpeg:
        // scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black
        effects.add(
            Presentation.createForWidthAndHeight(
                config.preset.targetWidth,
                config.preset.targetHeight,
                layoutMode
            )
        )

        // Equivalent to FFmpeg: unsharp=3:3:0.5:3:3:0.0
        if (config.sharpenLevel != SharpenLevel.OFF) {
            effects.add(UnsharpGlEffect(config.sharpenLevel.unsharpAmount))
        }

        return effects
    }

    @OptIn(UnstableApi::class)
    suspend fun processVideoVenlowStyle(
        videoUri: Uri,
        config: EncodingConfig,
        onStatusMessage: (String) -> Unit,
        onProgressPercent: (Int) -> Unit
    ): EncodedOutputVideo = withContext(Dispatchers.IO) {
        onStatusMessage("Preparando arquivo...")
        onProgressPercent(2)

        // 1. Copia o vídeo selecionado para o cache interno (input_temp.mp4)
        val inputFile = File(context.cacheDir, "input_temp.mp4")
        if (inputFile.exists()) inputFile.delete()

        if (videoUri.scheme == "file" && videoUri.path == inputFile.absolutePath) {
            // Already at input_temp.mp4
        } else {
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                FileOutputStream(inputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Não foi possível abrir o vídeo selecionado.")
        }

        val outputFile = File(context.cacheDir, "status_otimizado.mp4")
        if (outputFile.exists()) outputFile.delete()

        onStatusMessage("Codificando em Alta Definição para o Status...")
        onProgressPercent(5)

        // 2. Executa a codificação em Alta Definição (1080x1920, Unsharp, 30fps, H.264 High@4.0 3200k, AAC)
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(Uri.fromFile(inputFile))

                config.preset.maxDurationSec?.let { maxSec ->
                    mediaItemBuilder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0L)
                            .setEndPositionMs(maxSec * 1000L)
                            .build()
                    )
                }

                val editedMediaItem = EditedMediaItem.Builder(mediaItemBuilder.build())
                    .setFrameRate(config.preset.targetFps)
                    .setRemoveAudio(config.removeAudio)
                    .setEffects(
                        Effects(
                            /* audioProcessors = */ emptyList(),
                            /* videoEffects = */ buildVideoEffects(config)
                        )
                    )
                    .build()

                // Configure H.264 target bitrate (e.g. 3200 kbps) and High Profile @ Level 4.0
                val videoEncoderSettings = VideoEncoderSettings.Builder()
                    .setBitrate(config.preset.videoBitrateKbps * 1_000)
                    .setEncodingProfileLevel(
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel4
                    )
                    .build()

                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(videoEncoderSettings)
                    .build()

                val transformationRequestBuilder = TransformationRequest.Builder()
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)

                var progressJob: Job? = null

                val transformer = Transformer.Builder(context)
                    .setEncoderFactory(encoderFactory)
                    .setTransformationRequest(transformationRequestBuilder.build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            progressJob?.cancel()
                            onProgressPercent(95)
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            progressJob?.cancel()
                            if (continuation.isActive) {
                                continuation.resumeWithException(exportException)
                            }
                        }
                    })
                    .build()

                val compositionBuilder = Composition.Builder(
                    androidx.media3.transformer.EditedMediaItemSequence(editedMediaItem)
                )
                if (config.hdrToSdrToneMap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    compositionBuilder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC)
                }

                transformer.start(compositionBuilder.build(), outputFile.absolutePath)

                val progressHolder = ProgressHolder()
                progressJob = CoroutineScope(Dispatchers.Main).launch {
                    while (isActive) {
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val mapped = (5 + (progressHolder.progress * 0.88f)).toInt().coerceIn(5, 94)
                            onProgressPercent(mapped)
                        }
                        delay(120L)
                    }
                }

                continuation.invokeOnCancellation {
                    progressJob.cancel()
                    transformer.cancel()
                }
            }
        }

        // 3. Salva na Galeria em Movies/StatusHD e remove arquivos temporários
        onStatusMessage("Salvando em Filmes/StatusHD na Galeria...")
        val savedVideo = saveToGallery(outputFile, config)
        inputFile.delete()
        onProgressPercent(100)
        onStatusMessage("Concluído! Vídeo salvo na Galeria.")
        savedVideo
    }

    private suspend fun saveToGallery(file: File, config: EncodingConfig): EncodedOutputVideo = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val displayName = "StatusHD_$timestamp.mp4"
        val fileSize = file.length()

        val outputMeta = extractMetadata(Uri.fromFile(file))

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StatusHD")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val insertedUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Falha ao criar registro no MediaStore da Galeria.")

        resolver.openOutputStream(insertedUri)?.use { outputStream ->
            file.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IllegalStateException("Falha ao gravar bytes do vídeo na Galeria.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val completeValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(insertedUri, completeValues, null, null)
        }

        file.delete()

        EncodedOutputVideo(
            id = timestamp,
            contentUri = insertedUri,
            displayName = displayName,
            width = outputMeta.width,
            height = outputMeta.height,
            durationMs = outputMeta.durationMs,
            sizeBytes = fileSize,
            dateAddedSec = timestamp / 1000L,
            presetUsed = config.preset.title
        )
    }

    suspend fun loadSavedStatusHdVideos(): List<EncodedOutputVideo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<EncodedOutputVideo>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("StatusHD_%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext() && results.size < 20) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "StatusHD.mp4"
                    val width = cursor.getInt(widthCol).let { if (it > 0) it else 1080 }
                    val height = cursor.getInt(heightCol).let { if (it > 0) it else 1920 }
                    val duration = cursor.getLong(durCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    results.add(
                        EncodedOutputVideo(
                            id = id,
                            contentUri = contentUri,
                            displayName = name,
                            width = width,
                            height = height,
                            durationMs = duration,
                            sizeBytes = size,
                            dateAddedSec = dateAdded,
                            presetUsed = "1080×1920 H.264 High@4.0"
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        results
    }
}
