package com.example.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
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
 * Implements the exact pipeline from the user's StatusHD specification with automatic
 * hardware-codec fallback, dual persistence (MediaStore Gallery + internal FileProvider share cache),
 * and user-selected document export (`CreateDocument`).
 */
class VenlowVideoProcessor(private val context: Context) {

    private val exportedFilesDir: File
        get() = File(context.filesDir, "exported_status").apply {
            if (!exists()) mkdirs()
        }

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
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
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
            try {
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
            } catch (_: Exception) {
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
    fun buildVideoEffects(config: EncodingConfig, includeCustomShader: Boolean = true): List<Effect> {
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
        if (includeCustomShader && config.sharpenLevel != SharpenLevel.OFF) {
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
        if (inputFile.exists() && (videoUri.scheme != "file" || videoUri.path != inputFile.absolutePath)) {
            inputFile.delete()
        }

        if (videoUri.scheme == "file" && videoUri.path != null) {
            val sourceFile = File(videoUri.path!!)
            if (sourceFile.exists() && sourceFile.absolutePath != inputFile.absolutePath) {
                sourceFile.inputStream().use { input ->
                    FileOutputStream(inputFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
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

        // 2. Executa a codificação em Alta Definição:
        // Tentativa 1: H.264 High@4.0 + Unsharp Shader
        // Tentativa 2 (Fallback automático para emuladores/GPUs): Perfil padrão H.264 sem restrição estrita de ProfileLevel
        try {
            runTransformerExport(
                inputFile = inputFile,
                outputFile = outputFile,
                config = config,
                strictHighProfile = true,
                includeCustomShader = true,
                onProgressPercent = onProgressPercent
            )
        } catch (_: Exception) {
            if (outputFile.exists()) outputFile.delete()
            onStatusMessage("Otimizando compatibilidade do codificador H.264...")
            runTransformerExport(
                inputFile = inputFile,
                outputFile = outputFile,
                config = config,
                strictHighProfile = false,
                includeCustomShader = false,
                onProgressPercent = onProgressPercent
            )
        }

        // 3. Salva na Galeria (Movies/StatusHD) E mantém cópia segura para compartilhamento direto via FileProvider
        onStatusMessage("Salvando vídeo e preparando para o Status...")
        val savedVideo = saveToGalleryAndShareCache(outputFile, config)
        inputFile.delete()
        onProgressPercent(100)
        onStatusMessage("Concluído! Vídeo salvo na Galeria e pronto para o Status.")
        savedVideo
    }

    @OptIn(UnstableApi::class)
    private suspend fun runTransformerExport(
        inputFile: File,
        outputFile: File,
        config: EncodingConfig,
        strictHighProfile: Boolean,
        includeCustomShader: Boolean,
        onProgressPercent: (Int) -> Unit
    ) = withContext(Dispatchers.Main) {
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
                        /* videoEffects = */ buildVideoEffects(config, includeCustomShader)
                    )
                )
                .build()

            val videoEncoderSettingsBuilder = VideoEncoderSettings.Builder()
                .setBitrate(config.preset.videoBitrateKbps * 1_000)

            if (strictHighProfile) {
                videoEncoderSettingsBuilder.setEncodingProfileLevel(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel4
                )
            }

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(videoEncoderSettingsBuilder.build())
                .setEnableFallback(true)
                .build()

            val transformationRequestBuilder = TransformationRequest.Builder()
                .setVideoMimeType(MimeTypes.VIDEO_H264)
            if (!config.removeAudio) {
                transformationRequestBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            }

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
                EditedMediaItemSequence(editedMediaItem)
            )
            if (strictHighProfile && config.hdrToSdrToneMap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

    private suspend fun saveToGalleryAndShareCache(
        file: File,
        config: EncodingConfig
    ): EncodedOutputVideo = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val displayName = "StatusHD_$timestamp.mp4"
        val fileSize = file.length()
        val outputMeta = extractMetadata(Uri.fromFile(file))
        val finalWidth = outputMeta.width.let { if (it > 0) it else config.preset.targetWidth }
        val finalHeight = outputMeta.height.let { if (it > 0) it else config.preset.targetHeight }
        val finalDurationMs = outputMeta.durationMs.coerceAtLeast(1000L)

        // 1. Mantém uma cópia dedicada em filesDir/exported_status para compartilhamento 100% confiável via FileProvider
        val existingFiles = exportedFilesDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (existingFiles.size >= 8) {
            existingFiles.drop(7).forEach { it.delete() }
        }
        val localShareFile = File(exportedFilesDir, displayName)
        file.copyTo(localShareFile, overwrite = true)

        val fileProviderUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            localShareFile
        )

        // 2. Salva na Galeria pública (Movies/StatusHD e DCIM/Camera ou Movies) via MediaStore com metadados completos
        val galleryUri: Uri? = insertVideoIntoMediaStoreGallery(
            sourceFile = localShareFile,
            displayName = displayName,
            timestampMs = timestamp,
            width = finalWidth,
            height = finalHeight,
            durationMs = finalDurationMs,
            sizeBytes = fileSize
        )

        file.delete()

        EncodedOutputVideo(
            id = timestamp,
            contentUri = fileProviderUri,
            galleryUri = galleryUri ?: fileProviderUri,
            localFilePath = localShareFile.absolutePath,
            displayName = displayName,
            width = finalWidth,
            height = finalHeight,
            durationMs = finalDurationMs,
            sizeBytes = fileSize,
            dateAddedSec = timestamp / 1000L,
            presetUsed = config.preset.title
        )
    }

    /**
     * Insere o vídeo no MediaStore da Galeria Pública com todos os metadados exigidos pelo Google Fotos,
     * Galeria Samsung/Motorola/Xiaomi e WhatsApp, forçando a indexação imediata via MediaScannerConnection.
     */
    suspend fun insertVideoIntoMediaStoreGallery(
        sourceFile: File,
        displayName: String,
        timestampMs: Long,
        width: Int,
        height: Int,
        durationMs: Long,
        sizeBytes: Long
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val dateSec = timestampMs / 1000L

        // Em Android 10+ (API 29+), usamos RELATIVE_PATH = DCIM/StatusHD (ou Movies/StatusHD)
        // Nota: Muitos apps de Galeria (Samsung, Xiaomi, Google Fotos) priorizam pastas dentro de DCIM/ ou Movies/
        val relativeFoldersToTry = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(
                Environment.DIRECTORY_DCIM + "/StatusHD",
                Environment.DIRECTORY_MOVIES + "/StatusHD",
                Environment.DIRECTORY_MOVIES
            )
        } else {
            listOf("")
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        for (relativeFolder in relativeFoldersToTry) {
            var insertedUri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.TITLE, displayName.removeSuffix(".mp4"))
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATE_ADDED, dateSec)
                    put(MediaStore.Video.Media.DATE_MODIFIED, dateSec)
                    put(MediaStore.Video.Media.DATE_TAKEN, timestampMs)
                    put(MediaStore.Video.Media.WIDTH, width)
                    put(MediaStore.Video.Media.HEIGHT, height)
                    put(MediaStore.Video.Media.RESOLUTION, "${width}x${height}")
                    put(MediaStore.Video.Media.DURATION, durationMs)
                    put(MediaStore.Video.Media.SIZE, sizeBytes)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (relativeFolder.isNotEmpty()) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, relativeFolder)
                        }
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    } else {
                        val publicDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                            "StatusHD"
                        ).apply { if (!exists()) mkdirs() }
                        val targetPublicFile = File(publicDir, displayName)
                        sourceFile.copyTo(targetPublicFile, overwrite = true)
                        put(MediaStore.Video.Media.DATA, targetPublicFile.absolutePath)
                    }
                }

                insertedUri = resolver.insert(collection, values)
                if (insertedUri != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.openFileDescriptor(insertedUri, "w")?.use { pfd ->
                            FileOutputStream(pfd.fileDescriptor).use { outStream ->
                                sourceFile.inputStream().use { inStream ->
                                    inStream.copyTo(outStream)
                                }
                                outStream.flush()
                                pfd.fileDescriptor.sync()
                            }
                        } ?: resolver.openOutputStream(insertedUri)?.use { outStream ->
                            sourceFile.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                            outStream.flush()
                        }

                        val completeValues = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                            put(MediaStore.Video.Media.SIZE, sourceFile.length())
                        }
                        resolver.update(insertedUri, completeValues, null, null)
                    }

                    // Descobre o caminho físico real no armazenamento compartilhado (_DATA) para acionar o MediaScanner
                    var realPublicPath: String? = null
                    try {
                        resolver.query(
                            insertedUri,
                            arrayOf(MediaStore.Video.Media.DATA),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                                if (dataIdx != -1) {
                                    realPublicPath = cursor.getString(dataIdx)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }

                    val pathsToScan = buildList {
                        realPublicPath?.let { if (it.isNotBlank()) add(it) }
                        if (relativeFolder.isNotEmpty()) {
                            val extRoot = Environment.getExternalStorageDirectory()
                            val candidate = File(extRoot, "$relativeFolder/$displayName")
                            if (candidate.exists()) add(candidate.absolutePath)
                        }
                    }

                    if (pathsToScan.isNotEmpty()) {
                        try {
                            MediaScannerConnection.scanFile(
                                context,
                                pathsToScan.toTypedArray(),
                                Array(pathsToScan.size) { "video/mp4" },
                                null
                            )
                        } catch (_: Exception) {
                        }
                    }

                    @Suppress("DEPRECATION")
                    try {
                        context.sendBroadcast(
                            android.content.Intent(
                                android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                insertedUri
                            )
                        )
                    } catch (_: Exception) {
                    }

                    return@withContext insertedUri
                }
            } catch (_: Exception) {
                if (insertedUri != null) {
                    try {
                        resolver.delete(insertedUri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        null
    }

    /**
     * Prepara um FileProvider URI garantido para qualquer vídeo (inclusive itens da Galeria),
     * evitando erros de permissão quando o WhatsApp ou Instagram tenta ler o stream.
     */
    suspend fun prepareShareableFileProviderUri(video: EncodedOutputVideo): Uri = withContext(Dispatchers.IO) {
        // Se já temos o arquivo local em exported_status, retorna direto o FileProvider URI dele
        video.localFilePath?.let { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0L) {
                return@withContext FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    f
                )
            }
        }

        // Caso seja um item antigo do MediaStore, copia para exported_status e gera o FileProvider URI
        val targetFile = File(exportedFilesDir, video.displayName.ifBlank { "StatusHD_${video.id}.mp4" })
        if (!targetFile.exists() || targetFile.length() == 0L) {
            val sourceUri = video.galleryUri ?: video.contentUri
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            targetFile
        )
    }

    /**
     * Copia o vídeo convertido para um URI escolhido pelo usuário (ex: pasta Downloads via CreateDocument)
     * ou diretamente para a coleção MediaStore.Downloads.
     */
    suspend fun copyVideoToDestinationUri(video: EncodedOutputVideo, destinationUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val localFile = video.localFilePath?.let { File(it) }
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                if (localFile != null && localFile.exists()) {
                    localFile.inputStream().use { input ->
                        input.copyTo(outputStream)
                    }
                } else {
                    val srcUri = video.galleryUri ?: video.contentUri
                    context.contentResolver.openInputStream(srcUri)?.use { input ->
                        input.copyTo(outputStream)
                    } ?: return@withContext false
                }
            } ?: return@withContext false
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Salva uma cópia rápida diretamente na pasta pública "Downloads" do Android (Android 10+ MediaStore.Downloads).
     */
    suspend fun saveCopyToPublicDownloads(video: EncodedOutputVideo): Uri? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val fileName = video.displayName.ifBlank { "StatusHD_${System.currentTimeMillis()}.mp4" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StatusHD")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
                val copied = copyVideoToDestinationUri(video, uri)
                if (!copied) return@withContext null
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                return@withContext uri
            } else {
                return@withContext video.galleryUri ?: video.contentUri
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadSavedStatusHdVideos(): List<EncodedOutputVideo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<EncodedOutputVideo>()
        val seenNames = mutableSetOf<String>()

        // 1. Carrega primeiro os arquivos locais em exported_status (sempre disponíveis e rápidos)
        val localFiles = exportedFilesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp4") && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        for (file in localFiles) {
            val meta = extractMetadata(Uri.fromFile(file))
            val fpUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            seenNames.add(file.name)
            results.add(
                EncodedOutputVideo(
                    id = file.lastModified(),
                    contentUri = fpUri,
                    galleryUri = fpUri,
                    localFilePath = file.absolutePath,
                    displayName = file.name,
                    width = meta.width.let { if (it > 0) it else 1080 },
                    height = meta.height.let { if (it > 0) it else 1920 },
                    durationMs = meta.durationMs,
                    sizeBytes = file.length(),
                    dateAddedSec = file.lastModified() / 1000L,
                    presetUsed = "1080×1920 H.264 High@4.0"
                )
            )
        }

        // 2. Complementa com vídeos da Galeria (MediaStore) que não estejam no cache local
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
                    val name = cursor.getString(nameCol) ?: "StatusHD_$id.mp4"
                    if (name in seenNames) continue
                    seenNames.add(name)

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
                            galleryUri = contentUri,
                            localFilePath = null,
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
