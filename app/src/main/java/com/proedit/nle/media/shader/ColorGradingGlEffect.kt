package com.proedit.nle.media.shader

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.proedit.nle.domain.model.ColorGrade

@OptIn(UnstableApi::class)
class ColorGradingGlEffect(
    val colorGrade: ColorGrade
) : GlEffect {

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean
    ): GlShaderProgram {
        return ColorGradingShaderProgram(
            colorGrade = colorGrade,
            useHdr = useHdr
        )
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        return colorGrade.isBypassed
    }
}
