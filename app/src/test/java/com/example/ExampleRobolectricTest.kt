package com.example

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.media.VenlowVideoProcessor
import com.example.model.AspectFitMode
import com.example.model.EncodingConfig
import com.example.model.SharpenLevel
import com.example.model.StatusPreset
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("REDUXO VIDEO", appName)
    }

    @Test
    fun `verify default Venlow FFmpeg encoding command parameters`() {
        val defaultConfig = EncodingConfig(
            preset = StatusPreset.WHATSAPP_VENLOW,
            aspectFitMode = AspectFitMode.PAD_BLACK,
            sharpenLevel = SharpenLevel.VENLOW_DEFAULT,
            removeAudio = false
        )
        val cmd = defaultConfig.buildEquivalentFfmpegCommand("input_temp.mp4", "status_otimizado.mp4")

        val expectedExactVf = "-vf \"scale=1080:1920:force_original_aspect_ratio=decrease," +
            "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black," +
            "unsharp=3:3:0.5:3:3:0.0,fps=30,format=yuv420p\""

        assertTrue("Expected exact Venlow -vf chain", cmd.contains(expectedExactVf))
        assertTrue(cmd.contains("-c:v libx264 -preset medium -profile:v high -level 4.0"))
        assertTrue(cmd.contains("-b:v 3200k -maxrate 3500k -bufsize 7000k"))
        assertTrue(cmd.contains("-c:a aac -b:a 128k -ar 44100 -ac 2"))
        assertTrue(cmd.contains("-movflags +faststart"))
    }

    @Test
    fun `verify MediaStore cleanup when source file is empty or missing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val processor = VenlowVideoProcessor(context)
        val missingFile = File(context.cacheDir, "non_existent_8k_temp.mp4")
        if (missingFile.exists()) missingFile.delete()

        val resultUri = processor.insertVideoIntoMediaStoreGallery(
            sourceFile = missingFile,
            displayName = "StatusHD_test_empty.mp4",
            timestampMs = System.currentTimeMillis(),
            width = 1080,
            height = 1920,
            durationMs = 5000L,
            sizeBytes = 0L
        )
        assertNull("Empty/missing source file must not leave an entry in MediaStore", resultUri)

        // Ensure no zero-byte ghost entry exists in MediaStore
        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
            arrayOf("StatusHD_test_empty.mp4"),
            null
        )
        val count = cursor?.use { it.count } ?: 0
        assertEquals(0, count)
    }
}
