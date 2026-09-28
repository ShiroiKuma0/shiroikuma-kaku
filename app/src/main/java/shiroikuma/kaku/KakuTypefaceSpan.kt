package shiroikuma.kaku

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

/** A span in a given Typeface (TypefaceSpan(Typeface) is API 28+; this app runs from API 24). */
class KakuTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan()
{
    override fun updateDrawState(tp: TextPaint) = apply(tp)

    override fun updateMeasureState(tp: TextPaint) = apply(tp)

    private fun apply(paint: Paint)
    {
        paint.typeface = typeface
    }
}
