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
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Generates a real, high-resolution horizontal test video (1920x1080 landscape with 44.1 kHz stereo audio)
 * on device using FFmpeg (`libx264` + `aac`) so the user can immediately test how StatusHD scales,
 * pads to 1080x1920 vertical, sharpens (`unsharp=3:3:0.5:3:3:0.0`), and exports to MediaStore Gallery.
 */
object DemoVideoGenerator {

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

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }
        canvas.drawCircle(width / 2f, height / 2f, 380f, ringPaint)
        ringPaint.color = Color.parseColor("#00E676")
        canvas.drawCircle(width / 2f, height / 2f, 290f, ringPaint)

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
        canvas.drawText("FFmpeg libx264 • Unsharp 3x3 • 30 FPS • yuv420p", width / 2f, height / 2f + 115f, detailPaint)

        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        if (videoFile.exists()) videoFile.delete()

        // 2. Encode the PNG + silent stereo audio into a real 4-second 30fps MP4 clip using FFmpeg
        suspendCancellableCoroutine { continuation ->
            val cmd = "-y -loop 1 -i \"${imageFile.absolutePath}\" " +
                "-f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 " +
                "-t 4 -vf \"fps=30,format=yuv420p\" " +
                "-c:v libx264 -preset ultrafast -b:v 4000k " +
                "-c:a aac -b:a 128k -shortest " +
                "-movflags +faststart \"${videoFile.absolutePath}\""

            val session = FFmpegKit.executeAsync(cmd) { completed ->
                if (ReturnCode.isSuccess(completed.returnCode)) {
                    if (continuation.isActive) {
                        continuation.resume(Uri.fromFile(videoFile))
                    }
                } else {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Falha ao gerar clipe de teste via FFmpeg: ${completed.returnCode}")
                        )
                    }
                }
            }

            continuation.invokeOnCancellation {
                try {
                    FFmpegKit.cancel(session.sessionId)
                } catch (_: Exception) {
                }
            }
        }
    }
}
