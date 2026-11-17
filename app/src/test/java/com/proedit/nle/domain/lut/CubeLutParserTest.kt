package com.proedit.nle.domain.lut

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutParserTest {

    @Test
    fun `parses valid LUT_3D_SIZE 2 cube file with comments, TITLE, and DOMAIN bounds`() {
        val validCube = """
            # Sample Cinema 3D LUT
            TITLE "Rec709 Film Print"
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            LUT_3D_SIZE 2
            # 8 RGB corners
            0.01 0.02 0.03
            0.95 0.05 0.04
            0.02 0.92 0.08
            0.96 0.94 0.10
            0.03 0.06 0.91
            0.93 0.09 0.90
            0.08 0.95 0.94
            0.99 0.98 1.00
        """.trimIndent()

        val parsed = CubeLutParser.parse(validCube)
        assertEquals("Rec709 Film Print", parsed.title)
        assertEquals(2, parsed.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), parsed.domainMin, 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), parsed.domainMax, 1e-6f)
        assertEquals(24, parsed.rgbData.size)
        assertEquals(0.01f, parsed.rgbData[0], 1e-6f)
        assertEquals(1.00f, parsed.rgbData[23], 1e-6f)

        val rgbaBuffer = parsed.toDirectRgbaFloatBuffer()
        assertEquals(32, rgbaBuffer.capacity())
        assertEquals(0.01f, rgbaBuffer.get(0), 1e-6f)
        assertEquals(0.02f, rgbaBuffer.get(1), 1e-6f)
        assertEquals(0.03f, rgbaBuffer.get(2), 1e-6f)
        assertEquals(1.00f, rgbaBuffer.get(3), 1e-6f) // Alpha channel = 1.0f
    }

    @Test(expected = CubeLutParseException::class)
    fun `throws descriptive CubeLutParseException when RGB triplet count is incomplete`() {
        val truncatedCube = """
            TITLE "Broken LUT"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
        """.trimIndent()
        CubeLutParser.parse(truncatedCube)
    }

    @Test(expected = CubeLutParseException::class)
    fun `throws CubeLutParseException when LUT_3D_SIZE header is missing`() {
        val missingSizeCube = """
            TITLE "No Size Header"
            0.0 0.0 0.0
            1.0 1.0 1.0
        """.trimIndent()
        CubeLutParser.parse(missingSizeCube)
    }

    @Test
    fun `builtInPresets all parse without error`() {
        val presets = CubeLutParser.builtInPresets()
        assertTrue(presets.isNotEmpty())
        for ((name, content) in presets) {
            val parsed = CubeLutParser.parse(content)
            assertEquals(name, parsed.title)
            assertEquals(2, parsed.size)
        }
    }
}
