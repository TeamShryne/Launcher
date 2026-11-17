package com.proedit.nle.ui.scopes

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class VideoScopeMode(val label: String) {
    LUMA_WAVEFORM("LUMA"),
    RGB_PARADE("PARADE"),
    HISTOGRAM("HIST"),
    VECTORSCOPE("VECTOR")
}

data class ScopeAnalysisResult(
    val columns: Int = 128,
    val ireBuckets: Int = 64,
    val lumaWaveform: IntArray = IntArray(128 * 64),
    val paradeR: IntArray = IntArray(64 * 64),
    val paradeG: IntArray = IntArray(64 * 64),
    val paradeB: IntArray = IntArray(64 * 64),
    val histogramR: IntArray = IntArray(256),
    val histogramG: IntArray = IntArray(256),
    val histogramB: IntArray = IntArray(256),
    val histogramLuma: IntArray = IntArray(256),
    val vectorscopeGrid: IntArray = IntArray(64 * 64), // 64x64 (Cb, Cr) density grid
    val peakIre: Int = 0,
    val clippedPixelCount: Int = 0
)

object ScopeAnalyzer {

    /**
     * Downsamples the active preview frame buffer asynchronously on [Dispatchers.Default]
     * to compute Luma Waveform, RGB Parade, 256-bin RGB+Y Histogram, and Cb/Cr Vectorscope.
     */
    suspend fun analyzeFrame(
        bitmap: Bitmap,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): ScopeAnalysisResult = withContext(dispatcher) {
        val sampleWidth = 128
        val sampleHeight = 72
        val scaled = if (bitmap.width == sampleWidth && bitmap.height == sampleHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, false)
        }

        val pixels = IntArray(sampleWidth * sampleHeight)
        scaled.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)

        val ireBuckets = 64
        val paradeCols = 64
        val lumaWaveform = IntArray(sampleWidth * ireBuckets)
        val paradeR = IntArray(paradeCols * ireBuckets)
        val paradeG = IntArray(paradeCols * ireBuckets)
        val paradeB = IntArray(paradeCols * ireBuckets)
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        val histY = IntArray(256)
        val vectorSize = 64
        val vectorscopeGrid = IntArray(vectorSize * vectorSize)

        var peakIre = 0
        var clippedCount = 0

        for (y in 0 until sampleHeight) {
            val rowOffset = y * sampleWidth
            for (x in 0 until sampleWidth) {
                val argb = pixels[rowOffset + x]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                // Rec.709 Luma Y = 0.2126 R + 0.7152 G + 0.0722 B
                val luma255 = ((54 * r + 183 * g + 19 * b) shr 8).coerceIn(0, 255)
                val ire = (luma255 * 100) / 255
                if (ire > peakIre) peakIre = ire
                if (r >= 252 || g >= 252 || b >= 252) clippedCount++

                histR[r]++
                histG[g]++
                histB[b]++
                histY[luma255]++

                val yBucket = (luma255 * (ireBuckets - 1)) / 255
                lumaWaveform[x * ireBuckets + yBucket]++

                val pCol = (x * paradeCols) / sampleWidth
                val rBucket = (r * (ireBuckets - 1)) / 255
                val gBucket = (g * (ireBuckets - 1)) / 255
                val bBucket = (b * (ireBuckets - 1)) / 255
                paradeR[pCol * ireBuckets + rBucket]++
                paradeG[pCol * ireBuckets + gBucket]++
                paradeB[pCol * ireBuckets + bBucket]++

                // Rec.709 Cb / Cr chrominance in [-0.5, 0.5] mapped to [0, 63]
                val rf = r / 255f
                val gf = g / 255f
                val bf = b / 255f
                val yf = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
                val cb = (bf - yf) / 1.8556f // [-0.5, 0.5]
                val cr = (rf - yf) / 1.5748f // [-0.5, 0.5]

                val vx = ((cb + 0.5f) * (vectorSize - 1)).toInt().coerceIn(0, vectorSize - 1)
                val vy = ((0.5f - cr) * (vectorSize - 1)).toInt().coerceIn(0, vectorSize - 1)
                vectorscopeGrid[vy * vectorSize + vx]++
            }
        }

        ScopeAnalysisResult(
            columns = sampleWidth,
            ireBuckets = ireBuckets,
            lumaWaveform = lumaWaveform,
            paradeR = paradeR,
            paradeG = paradeG,
            paradeB = paradeB,
            histogramR = histR,
            histogramG = histG,
            histogramB = histB,
            histogramLuma = histY,
            vectorscopeGrid = vectorscopeGrid,
            peakIre = peakIre,
            clippedPixelCount = clippedCount
        )
    }
}
