package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.AspectFitMode
import com.example.model.EncodingConfig
import com.example.model.SharpenLevel
import com.example.model.StatusPreset
import org.junit.Assert.assertEquals
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
        assertEquals("StatusHD", appName)
    }

    @Test
    fun `verify default Venlow encoding command parameters`() {
        val defaultConfig = EncodingConfig(
            preset = StatusPreset.WHATSAPP_VENLOW,
            aspectFitMode = AspectFitMode.PAD_BLACK,
            sharpenLevel = SharpenLevel.VENLOW_DEFAULT
        )
        val cmd = defaultConfig.buildEquivalentFfmpegCommand("input_temp.mp4", "status_otimizado.mp4")

        assertTrue(cmd.contains("scale=1080:1920:force_original_aspect_ratio=decrease"))
        assertTrue(cmd.contains("pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black"))
        assertTrue(cmd.contains("unsharp=3:3:0.5:3:3:0.0"))
        assertTrue(cmd.contains("fps=30,format=yuv420p"))
        assertTrue(cmd.contains("-c:v libx264 -preset medium -profile:v high -level 4.0"))
        assertTrue(cmd.contains("-b:v 3200k -maxrate 3500k -bufsize 7000k"))
        assertTrue(cmd.contains("-c:a aac -b:a 128k -ar 44100 -ac 2"))
        assertTrue(cmd.contains("-movflags +faststart"))
    }
}
