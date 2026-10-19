package com.example.model

import android.net.Uri

enum class StatusPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val targetWidth: Int,
    val targetHeight: Int,
    val targetFps: Int,
    val videoBitrateKbps: Int,
    val maxBitrateKbps: Int,
    val bufSizeKbps: Int,
    val audioBitrateKbps: Int,
    val audioSampleRate: Int,
    val maxDurationSec: Int?
) {
    WHATSAPP_VENLOW(
        id = "whatsapp_venlow",
        title = "WhatsApp Status HD (Venlow)",
        subtitle = "1080×1920 • 30fps • 3.2 Mbps H.264 High@4.0",
        targetWidth = 1080,
        targetHeight = 1920,
        targetFps = 30,
        videoBitrateKbps = 3200,
        maxBitrateKbps = 3500,
        bufSizeKbps = 7000,
        audioBitrateKbps = 128,
        audioSampleRate = 44100,
        maxDurationSec = null
    ),
    WHATSAPP_SPLIT_30S(
        id = "whatsapp_30s",
        title = "WhatsApp Status Curto (30s)",
        subtitle = "Corta 30s • 1080×1920 • 30fps • 3.2 Mbps",
        targetWidth = 1080,
        targetHeight = 1920,
        targetFps = 30,
        videoBitrateKbps = 3200,
        maxBitrateKbps = 3500,
        bufSizeKbps = 7000,
        audioBitrateKbps = 128,
        audioSampleRate = 44100,
        maxDurationSec = 30
    ),
    INSTAGRAM_STORIES(
        id = "instagram_stories",
        title = "Instagram Stories / Reels HD",
        subtitle = "1080×1920 • 30fps • 4.5 Mbps Alta Nitidez",
        targetWidth = 1080,
        targetHeight = 1920,
        targetFps = 30,
        videoBitrateKbps = 4500,
        maxBitrateKbps = 5000,
        bufSizeKbps = 9000,
        audioBitrateKbps = 128,
        audioSampleRate = 44100,
        maxDurationSec = 60
    ),
    COMPACT_720P(
        id = "compact_720p",
        title = "Status Rápido 720p (Ultra Leve)",
        subtitle = "720×1280 • 30fps • 2.2 Mbps Upload Rápido",
        targetWidth = 720,
        targetHeight = 1280,
        targetFps = 30,
        videoBitrateKbps = 2200,
        maxBitrateKbps = 2500,
        bufSizeKbps = 5000,
        audioBitrateKbps = 128,
        audioSampleRate = 44100,
        maxDurationSec = null
    )
}

enum class AspectFitMode(
    val label: String,
    val description: String
) {
    PAD_BLACK(
        label = "Pad Vertical 9:16 (Venlow)",
        description = "Mantém proporção original e centraliza com barras pretas em 1080×1920"
    ),
    CROP_FILL(
        label = "Preencher Tela 9:16 (Zoom)",
        description = "Preenche 100% da tela vertical 1080×1920 cortando bordas laterais"
    )
}

enum class SharpenLevel(
    val label: String,
    val unsharpAmount: Float,
    val ffmpegParam: String
) {
    OFF("Desativado", 0.0f, "0.0"),
    VENLOW_DEFAULT("Nitidez Venlow (0.5)", 0.5f, "3:3:0.5:3:3:0.0"),
    CRISP_HD("Ultra Nitidez HD (0.85)", 0.85f, "3:3:0.85:3:3:0.0")
}

data class VideoMetadata(
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val frameRate: Float,
    val bitrateBps: Long,
    val rotationDegrees: Int,
    val mimeType: String
) {
    val formattedResolution: String
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) {
            "${height}×${width}"
        } else {
            "${width}×${height}"
        }

    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(1)
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return "%d:%02d".format(mins, secs)
        }

    val formattedSizeMb: String
        get() = "%.2f MB".format(sizeBytes / (1024.0 * 1024.0))

    val formattedBitrateMbps: String
        get() = if (bitrateBps > 0) {
            "%.1f Mbps".format(bitrateBps / 1_000_000.0)
        } else {
            "N/A"
        }
}

data class EncodedOutputVideo(
    val id: Long,
    val contentUri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val presetUsed: String
) {
    val formattedSizeMb: String
        get() = "%.2f MB".format(sizeBytes / (1024.0 * 1024.0))

    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(1)
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return "%d:%02d".format(mins, secs)
        }
}

data class EncodingConfig(
    val preset: StatusPreset = StatusPreset.WHATSAPP_VENLOW,
    val aspectFitMode: AspectFitMode = AspectFitMode.PAD_BLACK,
    val sharpenLevel: SharpenLevel = SharpenLevel.VENLOW_DEFAULT,
    val hdrToSdrToneMap: Boolean = true,
    val removeAudio: Boolean = false
) {
    fun buildEquivalentFfmpegCommand(inputPath: String = "input_temp.mp4", outputPath: String = "status_otimizado.mp4"): String {
        val w = preset.targetWidth
        val h = preset.targetHeight
        val scalePad = when (aspectFitMode) {
            AspectFitMode.PAD_BLACK ->
                "scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:black"
            AspectFitMode.CROP_FILL ->
                "scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h"
        }
        val unsharpPart = if (sharpenLevel != SharpenLevel.OFF) {
            ",unsharp=${sharpenLevel.ffmpegParam}"
        } else {
            ""
        }
        val filtroVideo = "$scalePad$unsharpPart,fps=${preset.targetFps},format=yuv420p"
        val audioFlags = if (removeAudio) {
            "-an"
        } else {
            "-c:a aac -b:a ${preset.audioBitrateKbps}k -ar ${preset.audioSampleRate} -ac 2"
        }
        val durationFlag = preset.maxDurationSec?.let { "-t $it " } ?: ""

        return "-y -i \"$inputPath\" $durationFlag" +
            "-vf \"$filtroVideo\" " +
            "-c:v libx264 -preset medium -profile:v high -level 4.0 " +
            "-b:v ${preset.videoBitrateKbps}k -maxrate ${preset.maxBitrateKbps}k -bufsize ${preset.bufSizeKbps}k " +
            "$audioFlags " +
            "-movflags +faststart \"$outputPath\""
    }
}
