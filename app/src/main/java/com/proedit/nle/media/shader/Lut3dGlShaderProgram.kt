package com.proedit.nle.media.shader

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import com.proedit.nle.domain.lut.ParsedCubeLut
import kotlin.math.floor

/**
 * Standalone OpenGL ES 3.0 Trilinear 3D LUT shader program and CPU trilinear reference evaluator
 * for both GPU video rendering and CPU preview frame / scope evaluation.
 */
@OptIn(UnstableApi::class)
class Lut3dGlShaderProgram(
    private val parsedLut: ParsedCubeLut,
    private val intensity: Float,
    useHdr: Boolean = false
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

    private var glProgram: GlProgram? = null
    private var lutTextureId: Int = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (glProgram == null) {
            try {
                val program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                program.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
                glProgram = program

                val texIds = IntArray(1)
                GLES30.glGenTextures(1, texIds, 0)
                lutTextureId = texIds[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

                GLES30.glTexImage3D(
                    GLES30.GL_TEXTURE_3D,
                    0,
                    GLES30.GL_RGBA16F,
                    parsedLut.size,
                    parsedLut.size,
                    parsedLut.size,
                    0,
                    GLES30.GL_RGBA,
                    GLES30.GL_FLOAT,
                    parsedLut.toDirectRgbaFloatBuffer()
                )
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = glProgram ?: return
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            val currentProgram = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, currentProgram, 0)
            val loc = GLES30.glGetUniformLocation(currentProgram[0], "uLut3dSampler")
            if (loc >= 0) {
                GLES30.glUniform1i(loc, 1)
            }

            program.setFloatUniform("uIntensity", intensity.coerceIn(0f, 1f))
            program.setFloatsUniform("uDomainMin", parsedLut.domainMin)
            program.setFloatsUniform("uDomainMax", parsedLut.domainMax)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram?.delete()
            if (lutTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                lutTextureId = 0
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            in vec4 aFramePosition;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            precision highp sampler3D;
            in vec2 vTexCoord;
            out vec4 outColor;
            uniform sampler2D uTexSampler;
            uniform sampler3D uLut3dSampler;
            uniform float uIntensity;
            uniform vec3 uDomainMin;
            uniform vec3 uDomainMax;

            void main() {
                vec4 src = texture(uTexSampler, vTexCoord);
                vec3 span = max(uDomainMax - uDomainMin, vec3(1e-5));
                vec3 uvw = clamp((src.rgb - uDomainMin) / span, vec3(0.0), vec3(1.0));
                vec3 graded = texture(uLut3dSampler, uvw).rgb;
                outColor = vec4(mix(src.rgb, graded, clamp(uIntensity, 0.0, 1.0)), src.a);
            }
        """

        /**
         * Performs trilinear interpolation on the CPU for scope analysis and procedural preview frames.
         */
        fun sampleTrilinearCpu(
            lut: ParsedCubeLut,
            r: Float,
            g: Float,
            b: Float,
            intensity: Float
        ): FloatArray {
            val n = lut.size
            val maxIndex = (n - 1).toFloat()
            val u = ((r - lut.domainMin[0]) / (lut.domainMax[0] - lut.domainMin[0]).coerceAtLeast(1e-5f))
                .coerceIn(0f, 1f) * maxIndex
            val v = ((g - lut.domainMin[1]) / (lut.domainMax[1] - lut.domainMin[1]).coerceAtLeast(1e-5f))
                .coerceIn(0f, 1f) * maxIndex
            val w = ((b - lut.domainMin[2]) / (lut.domainMax[2] - lut.domainMin[2]).coerceAtLeast(1e-5f))
                .coerceIn(0f, 1f) * maxIndex

            val r0 = floor(u).toInt().coerceIn(0, n - 1)
            val g0 = floor(v).toInt().coerceIn(0, n - 1)
            val b0 = floor(w).toInt().coerceIn(0, n - 1)
            val r1 = (r0 + 1).coerceAtMost(n - 1)
            val g1 = (g0 + 1).coerceAtMost(n - 1)
            val b1 = (b0 + 1).coerceAtMost(n - 1)

            val fr = u - r0
            val fg = v - g0
            val fb = w - b0

            fun voxel(ri: Int, gi: Int, bi: Int, channel: Int): Float {
                val idx = ((bi * n * n) + (gi * n) + ri) * 3 + channel
                return lut.rgbData[idx]
            }

            val out = FloatArray(3)
            for (ch in 0..2) {
                val c000 = voxel(r0, g0, b0, ch)
                val c100 = voxel(r1, g0, b0, ch)
                val c010 = voxel(r0, g1, b0, ch)
                val c110 = voxel(r1, g1, b0, ch)
                val c001 = voxel(r0, g0, b1, ch)
                val c101 = voxel(r1, g0, b1, ch)
                val c011 = voxel(r0, g1, b1, ch)
                val c111 = voxel(r1, g1, b1, ch)

                val c00 = c000 * (1f - fr) + c100 * fr
                val c01 = c001 * (1f - fr) + c101 * fr
                val c10 = c010 * (1f - fr) + c110 * fr
                val c11 = c011 * (1f - fr) + c111 * fr

                val c0 = c00 * (1f - fg) + c10 * fg
                val c1 = c01 * (1f - fg) + c11 * fg
                val lutVal = c0 * (1f - fb) + c1 * fb

                val orig = when (ch) {
                    0 -> r
                    1 -> g
                    else -> b
                }
                out[ch] = (orig + (lutVal - orig) * intensity.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            }
            return out
        }
    }
}
