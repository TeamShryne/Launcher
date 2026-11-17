package com.proedit.nle.domain.lut

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CubeLutParseException(message: String) : IllegalArgumentException(message)

data class ParsedCubeLut(
    val title: String,
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val rgbData: FloatArray
) {
    /**
     * Converts the RGB triplet array (size^3 * 3) into a direct RGBA FloatBuffer (size^3 * 4)
     * ready for OpenGL ES 3.0 `glTexImage3D(GL_TEXTURE_3D, ..., GL_RGBA16F, size, size, size, ..., GL_RGBA, GL_FLOAT, buffer)`.
     */
    fun toDirectRgbaFloatBuffer(): FloatBuffer {
        val totalVoxels = size * size * size
        val buffer = ByteBuffer
            .allocateDirect(totalVoxels * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (i in 0 until totalVoxels) {
            val base = i * 3
            buffer.put(rgbData[base])
            buffer.put(rgbData[base + 1])
            buffer.put(rgbData[base + 2])
            buffer.put(1.0f)
        }
        buffer.position(0)
        return buffer
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedCubeLut) return false
        return title == other.title &&
            size == other.size &&
            domainMin.contentEquals(other.domainMin) &&
            domainMax.contentEquals(other.domainMax) &&
            rgbData.contentEquals(other.rgbData)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + size
        result = 31 * result + domainMin.contentHashCode()
        result = 31 * result + domainMax.contentHashCode()
        result = 31 * result + rgbData.contentHashCode()
        return result
    }
}

object CubeLutParser {

    /**
     * Parses a standard Adobe/Iridas .cube 3D LUT string.
     * Supports comments (#), TITLE, LUT_3D_SIZE N, DOMAIN_MIN, DOMAIN_MAX, and N^3 RGB float triplets.
     */
    fun parse(cubeContent: String): ParsedCubeLut {
        if (cubeContent.isBlank()) {
            throw CubeLutParseException("Empty .cube content")
        }

        var title = "Untitled LUT"
        var lutSize = -1
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)
        var rgbData: FloatArray? = null
        var voxelWriteIndex = 0

        val lines = cubeContent.lineSequence()
        var lineNumber = 0

        for (rawLine in lines) {
            lineNumber++
            val stripped = rawLine.substringBefore('#').trim()
            if (stripped.isEmpty()) continue

            when {
                stripped.startsWith("TITLE", ignoreCase = true) -> {
                    val afterKeyword = stripped.substring(5).trim()
                    title = afterKeyword.removeSurrounding("\"").ifBlank { "Untitled LUT" }
                }
                stripped.startsWith("LUT_1D_SIZE", ignoreCase = true) -> {
                    throw CubeLutParseException("Line $lineNumber: 1D LUTs are not supported in 3D LUT parser")
                }
                stripped.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                    val parts = stripped.split(Regex("\\s+"))
                    if (parts.size != 2) {
                        throw CubeLutParseException("Line $lineNumber: Invalid LUT_3D_SIZE syntax '$stripped'")
                    }
                    val parsedSize = parts[1].toIntOrNull()
                        ?: throw CubeLutParseException("Line $lineNumber: Non-integer LUT_3D_SIZE '${parts[1]}'")
                    if (parsedSize < 2 || parsedSize > 128) {
                        throw CubeLutParseException("Line $lineNumber: LUT_3D_SIZE must be between 2 and 128, got $parsedSize")
                    }
                    lutSize = parsedSize
                    rgbData = FloatArray(parsedSize * parsedSize * parsedSize * 3)
                }
                stripped.startsWith("DOMAIN_MIN", ignoreCase = true) -> {
                    val values = parseTriplet(stripped.substring(10), lineNumber, "DOMAIN_MIN")
                    domainMin[0] = values[0]
                    domainMin[1] = values[1]
                    domainMin[2] = values[2]
                }
                stripped.startsWith("DOMAIN_MAX", ignoreCase = true) -> {
                    val values = parseTriplet(stripped.substring(10), lineNumber, "DOMAIN_MAX")
                    domainMax[0] = values[0]
                    domainMax[1] = values[1]
                    domainMax[2] = values[2]
                }
                stripped.startsWith("LUT_3D_INPUT_RANGE", ignoreCase = true) -> {
                    val parts = stripped.split(Regex("\\s+"))
                    if (parts.size == 3) {
                        val minVal = parts[1].toFloatOrNull() ?: 0f
                        val maxVal = parts[2].toFloatOrNull() ?: 1f
                        domainMin.fill(minVal)
                        domainMax.fill(maxVal)
                    }
                }
                else -> {
                    val targetArray = rgbData
                        ?: throw CubeLutParseException("Line $lineNumber: Encountered RGB data before LUT_3D_SIZE declaration")
                    val triplet = parseTriplet(stripped, lineNumber, "RGB triplet")
                    if (voxelWriteIndex + 3 > targetArray.size) {
                        throw CubeLutParseException(
                            "Line $lineNumber: Too many RGB triplets for LUT_3D_SIZE $lutSize (expected ${lutSize * lutSize * lutSize})"
                        )
                    }
                    targetArray[voxelWriteIndex++] = triplet[0]
                    targetArray[voxelWriteIndex++] = triplet[1]
                    targetArray[voxelWriteIndex++] = triplet[2]
                }
            }
        }

        if (lutSize < 2 || rgbData == null) {
            throw CubeLutParseException("Missing LUT_3D_SIZE header in .cube file")
        }

        for (c in 0..2) {
            if (domainMax[c] <= domainMin[c]) {
                throw CubeLutParseException(
                    "Invalid DOMAIN bounds: DOMAIN_MAX (${domainMax.contentToString()}) must be > DOMAIN_MIN (${domainMin.contentToString()})"
                )
            }
        }

        val expectedFloats = lutSize * lutSize * lutSize * 3
        if (voxelWriteIndex != expectedFloats) {
            throw CubeLutParseException(
                "Incomplete .cube data: expected ${expectedFloats / 3} RGB triplets for size $lutSize, but found ${voxelWriteIndex / 3}"
            )
        }

        return ParsedCubeLut(
            title = title,
            size = lutSize,
            domainMin = domainMin,
            domainMax = domainMax,
            rgbData = rgbData
        )
    }

    private fun parseTriplet(raw: String, lineNumber: Int, contextName: String): FloatArray {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size != 3) {
            throw CubeLutParseException("Line $lineNumber: Expected 3 floats for $contextName, got '${raw.trim()}'")
        }
        val r = parts[0].toFloatOrNull()
        val g = parts[1].toFloatOrNull()
        val b = parts[2].toFloatOrNull()
        if (r == null || g == null || b == null || !r.isFinite() || !g.isFinite() || !b.isFinite()) {
            throw CubeLutParseException("Line $lineNumber: Invalid floating-point values in $contextName '${raw.trim()}'")
        }
        return floatArrayOf(r, g, b)
    }

    /**
     * Built-in Cinema LUT presets (.cube format, size 2^3 = 8 corners) for immediate grading in the UI.
     */
    fun builtInPresets(): Map<String, String> = mapOf(
        "Teal & Orange 2383" to """
            # Kodak 2383 Inspired Teal & Orange Print Emulation
            TITLE "Teal & Orange 2383"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.020000 0.045000 0.075000
            0.940000 0.080000 0.040000
            0.030000 0.880000 0.140000
            0.980000 0.890000 0.100000
            0.010000 0.140000 0.920000
            0.920000 0.160000 0.880000
            0.080000 0.940000 0.950000
            1.000000 0.970000 0.920000
        """.trimIndent(),
        "Bleach Bypass Noir" to """
            # High-Contrast Silver Retention Look
            TITLE "Bleach Bypass Noir"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.000000 0.000000 0.010000
            0.780000 0.150000 0.140000
            0.160000 0.800000 0.180000
            0.920000 0.900000 0.250000
            0.120000 0.150000 0.820000
            0.850000 0.240000 0.860000
            0.220000 0.880000 0.900000
            0.990000 0.990000 1.000000
        """.trimIndent(),
        "Fuji Eterna Soft" to """
            # Soft Highlight Roll-Off Cinema Stock
            TITLE "Fuji Eterna Soft"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.035000 0.038000 0.045000
            0.890000 0.090000 0.090000
            0.070000 0.880000 0.110000
            0.940000 0.910000 0.140000
            0.060000 0.110000 0.890000
            0.900000 0.150000 0.890000
            0.120000 0.910000 0.920000
            0.960000 0.960000 0.950000
        """.trimIndent()
    )
}
