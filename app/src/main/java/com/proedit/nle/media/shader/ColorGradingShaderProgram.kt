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
import com.proedit.nle.domain.lut.CubeLutParser
import com.proedit.nle.domain.model.ColorGrade
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 3.00 single-pass color grading shader program implementing:
 * 1. Input texture sampling.
 * 2. White balance (Temperature/Tint chromatic adaptation matrix).
 * 3. Lift / Gamma / Gain / Offset ASC-CDL-inspired formula:
 *    C_out = pow(max(Gain * (C_in + Lift * (1.0 - C_in)) + Offset, 0.0), 1.0 / Gamma)
 * 4. S-Curve Contrast around `pivot` plus Shadows/Highlights recovery.
 * 5. Rec.709 Luma-preserving Saturation and skin-tone-protecting Vibrance.
 * 6. 1D RGB Curve texture lookup (Master, R, G, B).
 * 7. Trilinear 3D .cube sampler3D lookup blended by `lutIntensity`.
 */
@OptIn(UnstableApi::class)
class ColorGradingShaderProgram(
    private val colorGrade: ColorGrade,
    useHdr: Boolean
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

    private var glProgram: GlProgram? = null
    private var curveTextureId: Int = 0
    private var lut3dTextureId: Int = 0
    private var hasValid3dLut: Boolean = false
    private var lutDomainMin = floatArrayOf(0f, 0f, 0f)
    private var lutDomainMax = floatArrayOf(1f, 1f, 1f)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (glProgram == null) {
            try {
                val program = GlProgram(VERTEX_SHADER_ES30, FRAGMENT_SHADER_ES30)
                program.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
                glProgram = program
                upload1dCurveTexture()
                upload3dLutTexture()
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
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            program.setSamplerTexIdUniform("uCurveLutSampler", curveTextureId, /* texUnitIndex = */ 1)

            // Bind 3D LUT texture manually to unit 2 since GlProgram helper targets GL_TEXTURE_2D
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut3dTextureId)
            val lutLoc = GLES30.glGetUniformLocation(fetchProgramHandle(), "uLut3dSampler")
            if (lutLoc >= 0) {
                GLES30.glUniform1i(lutLoc, 2)
            }

            program.setFloatsUniform("uLift", colorGrade.effectiveLiftRgb())
            program.setFloatsUniform("uGamma", colorGrade.effectiveGammaRgb())
            program.setFloatsUniform("uGain", colorGrade.effectiveGainRgb())
            program.setFloatsUniform(
                "uOffset",
                floatArrayOf(colorGrade.offset.r, colorGrade.offset.g, colorGrade.offset.b)
            )
            program.setFloatUniform("uTemperature", (colorGrade.temperature / 100f).coerceIn(-1f, 1f))
            program.setFloatUniform("uTint", (colorGrade.tint / 100f).coerceIn(-1f, 1f))
            program.setFloatUniform("uContrast", colorGrade.contrast.coerceIn(0f, 2f))
            program.setFloatUniform("uPivot", colorGrade.pivot.coerceIn(0.05f, 0.95f))
            program.setFloatUniform("uSaturation", colorGrade.saturation.coerceIn(0f, 2f))
            program.setFloatUniform("uVibrance", colorGrade.vibrance.coerceIn(-1f, 1f))
            program.setFloatUniform("uHighlights", colorGrade.highlights.coerceIn(-1f, 1f))
            program.setFloatUniform("uShadows", colorGrade.shadows.coerceIn(-1f, 1f))

            val lutIntensity = if (hasValid3dLut && colorGrade.lut != null) {
                colorGrade.lut.intensity.coerceIn(0f, 1f)
            } else {
                0f
            }
            program.setFloatUniform("uLutIntensity", lutIntensity)
            program.setFloatsUniform("uLutDomainMin", lutDomainMin)
            program.setFloatsUniform("uLutDomainMax", lutDomainMax)

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
            if (curveTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(curveTextureId), 0)
                curveTextureId = 0
            }
            if (lut3dTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lut3dTextureId), 0)
                lut3dTextureId = 0
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun upload1dCurveTexture() {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        curveTextureId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val rgbaFloats = colorGrade.rgbCurves.toLut256RgbaFloatArray()
        val buffer: FloatBuffer = ByteBuffer
            .allocateDirect(rgbaFloats.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(rgbaFloats)
                position(0)
            }

        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA16F,
            256,
            1,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_FLOAT,
            buffer
        )
    }

    private fun upload3dLutTexture() {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        lut3dTextureId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut3dTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        val lutSpec = colorGrade.lut
        val parsed = if (lutSpec != null && lutSpec.cubeContent.isNotBlank()) {
            runCatching { CubeLutParser.parse(lutSpec.cubeContent) }.getOrNull()
        } else {
            null
        }

        if (parsed != null) {
            hasValid3dLut = true
            lutDomainMin = parsed.domainMin
            lutDomainMax = parsed.domainMax
            val buffer = parsed.toDirectRgbaFloatBuffer()
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D,
                0,
                GLES30.GL_RGBA16F,
                parsed.size,
                parsed.size,
                parsed.size,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_FLOAT,
                buffer
            )
        } else {
            hasValid3dLut = false
            // Upload 2x2x2 identity 3D texture so sampler3D is always valid
            val identity = CubeLutParser.parse(
                """
                LUT_3D_SIZE 2
                0.0 0.0 0.0
                1.0 0.0 0.0
                0.0 1.0 0.0
                1.0 1.0 0.0
                0.0 0.0 1.0
                1.0 0.0 1.0
                0.0 1.0 1.0
                1.0 1.0 1.0
                """.trimIndent()
            )
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D,
                0,
                GLES30.GL_RGBA16F,
                2,
                2,
                2,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_FLOAT,
                identity.toDirectRgbaFloatBuffer()
            )
        }
    }

    private fun fetchProgramHandle(): Int {
        val currentProgram = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, currentProgram, 0)
        return currentProgram[0]
    }

    companion object {
        const val VERTEX_SHADER_ES30 = """#version 300 es
            in vec4 aFramePosition;
            out vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        const val FRAGMENT_SHADER_ES30 = """#version 300 es
            precision highp float;
            precision highp sampler3D;

            in vec2 vTexSamplingCoord;
            out vec4 outColor;

            uniform sampler2D uTexSampler;
            uniform sampler2D uCurveLutSampler;
            uniform sampler3D uLut3dSampler;

            uniform vec3 uLift;
            uniform vec3 uGamma;
            uniform vec3 uGain;
            uniform vec3 uOffset;

            uniform float uTemperature;
            uniform float uTint;
            uniform float uContrast;
            uniform float uPivot;
            uniform float uSaturation;
            uniform float uVibrance;
            uniform float uHighlights;
            uniform float uShadows;

            uniform float uLutIntensity;
            uniform vec3 uLutDomainMin;
            uniform vec3 uLutDomainMax;

            const vec3 REC709_LUMA = vec3(0.2126, 0.7152, 0.0722);

            vec3 applyWhiteBalance(vec3 c, float temp, float tintVal) {
                // Bradford-inspired LMS chromatic adaptation approximation
                mat3 wbMat = mat3(
                    1.0 + 0.18 * temp - 0.05 * tintVal, 0.0, 0.0,
                    0.0, 1.0 - 0.12 * tintVal, 0.0,
                    0.0, 0.0, 1.0 - 0.18 * temp + 0.05 * tintVal
                );
                return max(wbMat * c, vec3(0.0));
            }

            vec3 applyLiftGammaGainOffset(vec3 c, vec3 lift, vec3 gamma, vec3 gain, vec3 offset) {
                vec3 lifted = gain * (c + lift * (vec3(1.0) - c)) + offset;
                vec3 clamped = max(lifted, vec3(0.0));
                vec3 invGamma = 1.0 / max(gamma, vec3(0.1));
                return pow(clamped, invGamma);
            }

            vec3 applySCurveContrastAndTone(vec3 c, float contrast, float pivot, float shadows, float highlights) {
                // Shadows & Highlights soft knee masks
                float luma = dot(c, REC709_LUMA);
                float shadowMask = clamp(1.0 - smoothstep(0.0, 0.55, luma), 0.0, 1.0);
                float highlightMask = clamp(smoothstep(0.45, 1.0, luma), 0.0, 1.0);
                vec3 toned = c + shadows * 0.25 * shadowMask + highlights * 0.25 * highlightMask;

                // Pivoted S-Curve Contrast
                vec3 safeTone = clamp(toned, vec3(1e-5), vec3(1.0));
                vec3 contrasted = pivot * pow(safeTone / max(pivot, 1e-4), vec3(contrast));
                return clamp(contrasted, vec3(0.0), vec3(1.0));
            }

            vec3 applySaturationAndSkinProtectedVibrance(vec3 c, float sat, float vib) {
                float luma = dot(c, REC709_LUMA);
                float maxCh = max(c.r, max(c.g, c.b));
                float minCh = min(c.r, min(c.g, c.b));
                float currentSat = maxCh - minCh;

                // Skin-tone protection around orange-red hue (R > G > B)
                float skinFactor = clamp((c.r - c.g) * 2.2, 0.0, 1.0) * clamp((c.g - c.b) * 2.0, 0.0, 1.0);
                float skinProtection = 1.0 - 0.65 * skinFactor;
                float vibranceBoost = 1.0 + vib * (1.0 - currentSat) * skinProtection;
                float totalSat = max(0.0, sat * vibranceBoost);
                return clamp(mix(vec3(luma), c, totalSat), vec3(0.0), vec3(1.0));
            }

            vec3 apply1dRgbCurves(vec3 c) {
                // Sample individual R, G, B curves
                float rCurved = texture(uCurveLutSampler, vec2(clamp(c.r, 0.0, 1.0), 0.5)).r;
                float gCurved = texture(uCurveLutSampler, vec2(clamp(c.g, 0.0, 1.0), 0.5)).g;
                float bCurved = texture(uCurveLutSampler, vec2(clamp(c.b, 0.0, 1.0), 0.5)).b;
                // Sample Master curve stored in Alpha channel
                float rOut = texture(uCurveLutSampler, vec2(clamp(rCurved, 0.0, 1.0), 0.5)).a;
                float gOut = texture(uCurveLutSampler, vec2(clamp(gCurved, 0.0, 1.0), 0.5)).a;
                float bOut = texture(uCurveLutSampler, vec2(clamp(bCurved, 0.0, 1.0), 0.5)).a;
                return vec3(rOut, gOut, bOut);
            }

            vec3 apply3dCubeLut(vec3 c) {
                if (uLutIntensity <= 0.0001) return c;
                vec3 domainSpan = max(uLutDomainMax - uLutDomainMin, vec3(1e-5));
                vec3 uvw = clamp((c - uLutDomainMin) / domainSpan, vec3(0.0), vec3(1.0));
                vec3 lutColor = texture(uLut3dSampler, uvw).rgb;
                return mix(c, lutColor, clamp(uLutIntensity, 0.0, 1.0));
            }

            void main() {
                vec4 src = texture(uTexSampler, vTexSamplingCoord);
                vec3 color = src.rgb;

                // 1. White Balance
                color = applyWhiteBalance(color, uTemperature, uTint);
                // 2. Lift / Gamma / Gain / Offset
                color = applyLiftGammaGainOffset(color, uLift, uGamma, uGain, uOffset);
                // 3. Contrast around Pivot + Shadows/Highlights
                color = applySCurveContrastAndTone(color, uContrast, uPivot, uShadows, uHighlights);
                // 4. Rec.709 Luma-preserving Saturation + Skin-protected Vibrance
                color = applySaturationAndSkinProtectedVibrance(color, uSaturation, uVibrance);
                // 5. 1D Monotone Cubic Hermite RGB Curves
                color = apply1dRgbCurves(color);
                // 6. Trilinear 3D .cube LUT lookup
                color = apply3dCubeLut(color);

                outColor = vec4(clamp(color, vec3(0.0), vec3(1.0)), src.a);
            }
        """
    }
}
