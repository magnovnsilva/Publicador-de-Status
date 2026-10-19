package com.example.media

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Custom OpenGL ES 2.0 3x3 Unsharp Mask (Laplacian Luma Sharpening) video effect for Media3.
 * Replicates FFmpeg's `unsharp=3:3:amount:3:3:0.0` filter in hardware on the GPU.
 */
@OptIn(UnstableApi::class)
class UnsharpGlEffect(private val amount: Float) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return UnsharpShaderProgram(useHdr, amount)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        return amount <= 0.01f
    }
}

@OptIn(UnstableApi::class)
private class UnsharpShaderProgram(
    useHdr: Boolean,
    private val amount: Float
) : BaseGlShaderProgram(useHdr, /* inputTexturePoolCapacity= */ 1) {

    private var glProgram: GlProgram? = null
    private var inputWidth: Int = 1080
    private var inputHeight: Int = 1920

    init {
        val vertexShader = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """.trimIndent()

        // 3x3 Gaussian-weighted Unsharp Mask matching FFmpeg's unsharp=3:3:amount:3:3:0.0
        val fragmentShader = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uAmount;
            varying vec2 vTexSamplingCoord;

            void main() {
                vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
                vec4 n  = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -uTexelSize.y));
                vec4 s  = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0,  uTexelSize.y));
                vec4 e  = texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelSize.x, 0.0));
                vec4 w  = texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelSize.x, 0.0));
                vec4 ne = texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelSize.x, -uTexelSize.y));
                vec4 nw = texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelSize.x, -uTexelSize.y));
                vec4 se = texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelSize.x,  uTexelSize.y));
                vec4 sw = texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelSize.x,  uTexelSize.y));

                // 3x3 Gaussian blur kernel: [1 2 1; 2 4 2; 1 2 1] / 16.0
                vec3 blur = (
                    center.rgb * 4.0 +
                    (n.rgb + s.rgb + e.rgb + w.rgb) * 2.0 +
                    (ne.rgb + nw.rgb + se.rgb + sw.rgb)
                ) / 16.0;

                // Unsharp mask: original + amount * (original - blurred)
                vec3 sharpened = clamp(center.rgb + uAmount * (center.rgb - blur), 0.0, 1.0);
                gl_FragColor = vec4(sharpened, center.a);
            }
        """.trimIndent()

        glProgram = GlProgram(vertexShader, fragmentShader).apply {
            setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth.coerceAtLeast(1)
        this.inputHeight = inputHeight.coerceAtLeast(1)
        return Size(this.inputWidth, this.inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = glProgram ?: return
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
        program.setFloatsUniform(
            "uTexelSize",
            floatArrayOf(1.0f / inputWidth.toFloat(), 1.0f / inputHeight.toFloat())
        )
        program.setFloatUniform("uAmount", amount)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        GlUtil.checkGlError()
    }

    override fun release() {
        super.release()
        glProgram?.delete()
        glProgram = null
    }
}
