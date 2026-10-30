package com.example.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.example.model.EncodedOutputVideo
import com.example.model.EncodingConfig
import com.example.model.VideoMetadata
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Industrial-strength FFmpeg-powered Video Transcoding Engine (`ffmpeg-kit`).
 *
 * Replaces fragile hardware MediaCodec / custom OpenGL ES shaders with real embedded FFmpeg (`libx264` + `unsharp`)
 * so that 8K, 4K, 60fps, 120fps, HEVC, and 10-bit HDR inputs transcode reliably into a 1080x1920
 * vertical H.264 High@4.0 `yuv420p` MP4 with `-movflags +faststart` and zero black-frame bugs.
 */
class VenlowVideoProcessor(private val context: Context) {

    @Volatile
    private var activeSessionId: Long? = null

    private val exportedFilesDir: File
        get() = File(context.filesDir, "exported_status").apply {
            if (!exists()) mkdirs()
        }

    fun cancelActiveSession() {
        val sessionId = activeSessionId
        if (sessionId != null) {
            try {
                FFmpegKit.cancel(sessionId)
            } catch (_: Exception) {
            }
            activeSessionId = null
        } else {
            try {
                FFmpegKit.cancel()
            } catch (_: Exception) {
            }
        }
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

    /**
     * Executes the real FFmpeg transcoding pipeline asynchronously (`FFmpegKit.executeAsync`):
     * - Copies source video to `cacheDir/input_temp.mp4`
     * - Runs `scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black,unsharp=3:3:0.5:3:3:0.0,fps=30,format=yuv420p`
     * - Encodes with `libx264 -preset medium -profile:v high -level 4.0 -b:v 3200k -maxrate 3500k -bufsize 7000k`
     * - Encodes audio with `aac -b:a 128k -ar 44100 -ac 2` and `-movflags +faststart`
     * - Saves to MediaStore Gallery with atomic `IS_PENDING` cleanup on failure or cancellation.
     */
    suspend fun processVideoVenlowStyle(
        videoUri: Uri,
        config: EncodingConfig,
        onStatusMessage: (String) -> Unit,
        onProgressPercent: (Int) -> Unit
    ): EncodedOutputVideo = withContext(Dispatchers.IO) {
        onStatusMessage("Preparando arquivo...")
        onProgressPercent(2)

        val inputFile = File(context.cacheDir, "input_temp.mp4")
        val outputFile = File(context.cacheDir, "status_otimizado.mp4")

        try {
            // 1. Copia o vídeo selecionado para o cache interno para o FFmpeg ler
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

            if (outputFile.exists()) outputFile.delete()

            val inputMeta = extractMetadata(Uri.fromFile(inputFile))
            val effectiveTotalDurationMs = config.preset.maxDurationSec
                ?.let { minOf(inputMeta.durationMs.coerceAtLeast(1000L), it * 1000L) }
                ?: inputMeta.durationMs.coerceAtLeast(1000L)

            onStatusMessage("Codificando em Alta Definição para o Status...")
            onProgressPercent(5)

            // 2. Comando FFmpeg idêntico ao Venlow (1080x1920 vertical, unsharp, 30fps, H.264 High@4.0 yuv420p, faststart)
            val ffmpegCommand = config.buildEquivalentFfmpegCommand(
                inputPath = inputFile.absolutePath,
                outputPath = outputFile.absolutePath
            )

            runFfmpegSession(
                command = ffmpegCommand,
                totalDurationMs = effectiveTotalDurationMs,
                onProgressPercent = onProgressPercent
            )

            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw IllegalStateException("Arquivo de saída vazio após codificação FFmpeg.")
            }

            // 3. Salva na Galeria (MediaStore) e no cache de compartilhamento FileProvider
            onStatusMessage("Salvando em Filmes/StatusHD na Galeria...")
            val savedVideo = saveToGalleryAndShareCache(outputFile, config)
            onProgressPercent(100)
            onStatusMessage("Concluído! Vídeo salvo na Galeria.")
            savedVideo
        } catch (ce: CancellationException) {
            if (outputFile.exists()) outputFile.delete()
            throw ce
        } catch (e: Exception) {
            if (outputFile.exists()) outputFile.delete()
            throw e
        } finally {
            activeSessionId = null
            if (inputFile.exists()) {
                inputFile.delete()
            }
        }
    }

    private suspend fun runFfmpegSession(
        command: String,
        totalDurationMs: Long,
        onProgressPercent: (Int) -> Unit
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        FFmpegKitConfig.enableStatisticsCallback { statistics ->
            if (statistics != null && totalDurationMs > 0L) {
                val timeMs = statistics.time.toLong().coerceAtLeast(0L)
                val rawPct = ((timeMs.toDouble() / totalDurationMs.toDouble()) * 88.0).toInt()
                val mappedPct = (5 + rawPct).coerceIn(5, 94)
                onProgressPercent(mappedPct)
            }
        }

        val session = FFmpegKit.executeAsync(command) { completedSession ->
            FFmpegKitConfig.enableStatisticsCallback(null)
            activeSessionId = null
            val returnCode = completedSession.returnCode

            when {
                ReturnCode.isSuccess(returnCode) -> {
                    onProgressPercent(95)
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                ReturnCode.isCancel(returnCode) -> {
                    if (continuation.isActive) {
                        continuation.cancel(CancellationException("Codificação FFmpeg cancelada pelo usuário."))
                    }
                }
                else -> {
                    val failTail = completedSession.failStackTrace
                        ?: completedSession.allLogsAsString?.takeLast(320)
                        ?: "Código de retorno FFmpeg: $returnCode"
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Erro ao processar vídeo no FFmpeg: $failTail")
                        )
                    }
                }
            }
        }

        activeSessionId = session.sessionId

        continuation.invokeOnCancellation {
            FFmpegKitConfig.enableStatisticsCallback(null)
            try {
                FFmpegKit.cancel(session.sessionId)
            } catch (_: Exception) {
            }
            activeSessionId = null
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

        // 2. Salva na Galeria pública (Movies/StatusHD e DCIM/StatusHD) via MediaStore com proteção contra IS_PENDING órfão
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
     * Insere o vídeo no MediaStore da Galeria Pública com try-catch-finally rigoroso:
     * se a gravação falhar ou for cancelada, a entrada `IS_PENDING = 1` é imediatamente removida
     * (`resolver.delete(insertedUri, null, null)`) para nunca deixar arquivos fantasmas ou pretos na Galeria.
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
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return@withContext null
        }

        val resolver = context.contentResolver
        val dateSec = timestampMs / 1000L

        val relativeFoldersToTry = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(
                Environment.DIRECTORY_MOVIES + "/StatusHD",
                Environment.DIRECTORY_DCIM + "/StatusHD",
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
            var writeSucceeded = false
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
                        var bytesWritten = 0L
                        resolver.openFileDescriptor(insertedUri, "w")?.use { pfd ->
                            FileOutputStream(pfd.fileDescriptor).use { outStream ->
                                sourceFile.inputStream().use { inStream ->
                                    bytesWritten = inStream.copyTo(outStream)
                                }
                                outStream.flush()
                                pfd.fileDescriptor.sync()
                            }
                        } ?: resolver.openOutputStream(insertedUri)?.use { outStream ->
                            sourceFile.inputStream().use { inStream ->
                                bytesWritten = inStream.copyTo(outStream)
                            }
                            outStream.flush()
                        } ?: throw IllegalStateException("Não foi possível abrir OutputStream do MediaStore.")

                        if (bytesWritten <= 0L) {
                            throw IllegalStateException("Nenhum byte foi gravado no MediaStore.")
                        }

                        val completeValues = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                            put(MediaStore.Video.Media.SIZE, bytesWritten)
                        }
                        resolver.update(insertedUri, completeValues, null, null)
                    }

                    writeSucceeded = true

                    // Consulta o caminho físico real (_DATA) para acionar o MediaScanner imediatamente
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
                            Intent(
                                Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                insertedUri
                            )
                        )
                    } catch (_: Exception) {
                    }

                    return@withContext insertedUri
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Tenta a próxima pasta se houver erro específico de diretório
            } finally {
                if (!writeSucceeded && insertedUri != null) {
                    try {
                        resolver.delete(insertedUri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        null
    }

    suspend fun prepareShareableFileProviderUri(video: EncodedOutputVideo): Uri = withContext(Dispatchers.IO) {
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
                outputStream.flush()
            } ?: return@withContext false
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun saveCopyToPublicDownloads(video: EncodedOutputVideo): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val fileName = video.displayName.ifBlank { "StatusHD_${System.currentTimeMillis()}.mp4" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var insertedUri: Uri? = null
            var success = false
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StatusHD")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                insertedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
                val copied = copyVideoToDestinationUri(video, insertedUri)
                if (!copied) return@withContext null
                val done = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                    put(MediaStore.Downloads.SIZE, video.sizeBytes)
                }
                resolver.update(insertedUri, done, null, null)
                success = true
                return@withContext insertedUri
            } catch (_: Exception) {
                return@withContext null
            } finally {
                if (!success && insertedUri != null) {
                    try {
                        resolver.delete(insertedUri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            return@withContext video.galleryUri ?: video.contentUri
        }
    }

    suspend fun loadSavedStatusHdVideos(): List<EncodedOutputVideo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<EncodedOutputVideo>()
        val seenNames = mutableSetOf<String>()

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
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0L) continue
                    seenNames.add(name)

                    val width = cursor.getInt(widthCol).let { if (it > 0) it else 1080 }
                    val height = cursor.getInt(heightCol).let { if (it > 0) it else 1920 }
                    val duration = cursor.getLong(durCol)
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
