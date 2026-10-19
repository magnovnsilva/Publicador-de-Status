package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Generates a real, high-resolution horizontal test video (1920x1080 landscape) on device
 * using Media3 Transformer Image-to-Video encoding so the user can immediately test how
 * StatusHD scales, pads to 1080x1920 vertical, sharpens (unsharp), and encodes for WhatsApp Status.
 */
object DemoVideoGenerator {

    @OptIn(UnstableApi::class)
    suspend fun generateTestClip(context: Context): Uri = withContext(Dispatchers.IO) {
        val imageFile = File(context.cacheDir, "demo_source_frame.png")
        val videoFile = File(context.cacheDir, "demo_source_1080p.mp4")
        if (videoFile.exists() && videoFile.length() > 10_000L) {
            return@withContext Uri.fromFile(videoFile)
        }

        // 1. Render a crisp 1920x1080 landscape studio test pattern bitmap
        val width = 1920
        val height = 1080
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Deep gradient background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(
                    Color.parseColor("#0A192F"),
                    Color.parseColor("#093028"),
                    Color.parseColor("#112240")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Draw high-frequency grid lines to showcase Unsharp Mask sharpening & anti-compression
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 0, 230, 118)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        for (x in 0..width step 120) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint)
        }
        for (y in 0..height step 120) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), gridPaint)
        }

        // Concentric sharpness calibration rings
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }
        canvas.drawCircle(width / 2f, height / 2f, 380f, ringPaint)
        ringPaint.color = Color.parseColor("#00E676")
        canvas.drawCircle(width / 2f, height / 2f, 290f, ringPaint)

        // Center badge card
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 12, 22, 34)
            style = Paint.Style.FILL
        }
        val cardRect = RectF(width / 2f - 540f, height / 2f - 210f, width / 2f + 540f, height / 2f + 210f)
        canvas.drawRoundRect(cardRect, 36f, 36f, cardPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E676")
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(cardRect, 36f, 36f, borderPaint)

        // Typography inside test card
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 76f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("STATUS HD • 1080P TEST SOURCE", width / 2f, height / 2f - 55f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E676")
            textSize = 44f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("1920x1080 Landscape -> 1080x1920 Vertical Pad", width / 2f, height / 2f + 30f, subPaint)

        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9BAEC2")
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Unsharp 3x3 Luma • 30 FPS • H.264 High@4.0 • 3200 kbps", width / 2f, height / 2f + 115f, detailPaint)

        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        if (videoFile.exists()) videoFile.delete()

        // 2. Encode the PNG into a real 4-second 30fps MP4 clip on the main looper using Media3 Transformer
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val imageMediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(imageFile))
                    .setImageDurationMs(4_000L)
                    .build()

                val editedItem = EditedMediaItem.Builder(imageMediaItem)
                    .setFrameRate(30)
                    .setEffects(
                        Effects(
                            emptyList(),
                            listOf(Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT))
                        )
                    )
                    .build()

                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) {
                                continuation.resume(Uri.fromFile(videoFile))
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(exportException)
                            }
                        }
                    })
                    .build()

                transformer.start(editedItem, videoFile.absolutePath)

                continuation.invokeOnCancellation {
                    transformer.cancel()
                }
            }
        }
    }
}
