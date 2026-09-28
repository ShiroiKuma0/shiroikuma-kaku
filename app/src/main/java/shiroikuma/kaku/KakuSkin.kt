package shiroikuma.kaku

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import ca.fuwafuwa.kaku.R

/**
 * Paints 白い熊 画 from [KakuUi]: the overlay windows (result window, instant popups, kanji
 * choice, editor, capture box) and the app's own screens. Every drawable is built fresh from the
 * current settings, so a window that repaints on a new [KakuUi.stamp] shows what the UI page set.
 */
object KakuSkin
{
    fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    private fun box(context: Context, fill: Int, stroke: Int, borderKey: String, radiusDp: Float): GradientDrawable
    {
        val g = GradientDrawable()
        g.setColor(fill)
        val w = KakuUi.borderPx(context, borderKey)
        if (w > 0) g.setStroke(w, stroke)
        g.cornerRadius = dp(context, radiusDp)
        return g
    }

    // ---- overlay windows ------------------------------------------------------------------------

    /** The result window / instant popup panel. */
    @JvmStatic
    fun windowPanel(context: Context): Drawable =
            box(context, KakuUi.i(KakuUi.C_WIN_BG), KakuUi.i(KakuUi.C_WIN_BORDER), KakuUi.WIN_BORDER_W,
                    KakuUi.i(KakuUi.WIN_RADIUS).toFloat())

    /** Opacity of the lookup windows (the capture box keeps its own: its red ready line must read). */
    @JvmStatic
    fun windowAlpha(): Float = KakuUi.i(KakuUi.WIN_ALPHA).coerceIn(20, 100) / 100f

    /** A recognised character that is part of the looked-up word. */
    @JvmStatic
    fun charHighlight(context: Context): Drawable =
            box(context, KakuUi.i(KakuUi.C_CHAR_HL_FILL), KakuUi.i(KakuUi.C_CHAR_HL_BORDER), KakuUi.CHAR_HL_BORDER_W, 0f)

    /** The character under the finger: the highlight border alone. */
    @JvmStatic
    fun charTouched(context: Context): Drawable =
            box(context, Color.TRANSPARENT, KakuUi.i(KakuUi.C_CHAR_HL_BORDER), KakuUi.CHAR_HL_BORDER_W, 0f)

    /**
     * The side of one recognised-character cell: the character's own size and a hair of leading, so
     * the characters sit side by side like ordinary Japanese text, with no gaps between them.
     */
    @JvmStatic
    fun charCellPx(context: Context): Int = Math.round(dp(context, KakuUi.i(KakuUi.CHAR_FONT_SIZE) * 1.1f))

    @JvmStatic
    fun charTypeface(context: Context): Typeface =
            KakuFonts.at(context, KakuUi.s(KakuUi.CHAR_FONT_FAMILY), KakuUi.i(KakuUi.CHAR_FONT_WEIGHT), false)

    @JvmStatic
    fun dictTypeface(context: Context): Typeface =
            KakuFonts.at(context, KakuUi.s(KakuUi.DICT_FONT_FAMILY), KakuUi.i(KakuUi.DICT_FONT_WEIGHT), false)

    @JvmStatic
    fun dictHeadTypeface(context: Context): Typeface =
            KakuFonts.at(context, KakuUi.s(KakuUi.DICT_FONT_FAMILY), maxOf(700, KakuUi.i(KakuUi.DICT_FONT_WEIGHT)), false)

    /** A candidate cell of the kanji choice window. */
    @JvmStatic
    fun choiceCell(context: Context, active: Boolean): Drawable
    {
        val g = GradientDrawable()
        g.setColor(KakuUi.i(if (active) KakuUi.C_CHOICE_ACTIVE else KakuUi.C_CHOICE_BG))
        g.setStroke(maxOf(1, dp(context, 1f).toInt()), KakuUi.i(KakuUi.C_CHOICE_BORDER))
        return g
    }

    /** The cropped OCR image tile of the kanji choice window. */
    @JvmStatic
    fun choiceImage(context: Context): Drawable
    {
        val g = GradientDrawable()
        g.setColor(KakuUi.i(KakuUi.C_CHOICE_BG))
        g.setStroke(maxOf(1, dp(context, 1f).toInt()), KakuUi.i(KakuUi.C_CHOICE_BORDER))
        return g
    }

    /** The colour a candidate is drawn in, by its script. */
    @JvmStatic
    fun choiceTextColor(c: Char): Int = KakuUi.i(when
    {
        c in '぀'..'ゟ' -> KakuUi.C_CHOICE_HIRAGANA
        c in '゠'..'ヿ' -> KakuUi.C_CHOICE_KATAKANA
        c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' -> KakuUi.C_CHOICE_KANJI
        else -> KakuUi.C_CHOICE_OTHER
    })

    /**
     * The capture box frame, 1 dp of the capture border colour; when [ready] the outermost pixel
     * line is the red that CaptureWindow.checkScreenshotIsReady looks for in the screenshot.
     */
    @JvmStatic
    fun captureFrame(context: Context, ready: Boolean, busy: Boolean): Drawable
    {
        val frame = GradientDrawable()
        frame.setColor(if (busy) KakuUi.i(KakuUi.C_CAPTURE_BUSY) else Color.TRANSPARENT)
        frame.setStroke(maxOf(1, dp(context, 1f).toInt()), KakuUi.i(KakuUi.C_CAPTURE_BORDER))
        if (!ready) return frame
        val red = GradientDrawable()
        red.setColor(Color.TRANSPARENT)
        red.setStroke(1, ContextCompat.getColor(context, R.color.red_capture_window_ready))
        return LayerDrawable(arrayOf(frame, red))
    }

    // ---- app screens ----------------------------------------------------------------------------

    /**
     * Paint an activity's content in the page colours: ground, text, secondary text, links /
     * progress in the accent, interface font. Views tagged [KakuViews.NO_SKIN] are left alone.
     */
    @JvmStatic
    fun applyToActivity(activity: Activity)
    {
        val bg = KakuUi.i(KakuUi.C_BG)
        activity.window?.let {
            it.setBackgroundDrawable(ColorDrawable(bg))
            @Suppress("DEPRECATION")
            it.statusBarColor = bg
            @Suppress("DEPRECATION")
            it.navigationBarColor = bg
        }
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        applyToTree(content)
    }

    @JvmStatic
    fun applyToTree(root: View)
    {
        val ctx = root.context
        val bg = KakuUi.i(KakuUi.C_BG)
        val text = KakuUi.i(KakuUi.C_TEXT)
        val accent = KakuUi.i(KakuUi.C_ACCENT)
        val face = KakuFonts.at(ctx, KakuUi.s(KakuUi.FONT_FAMILY), KakuUi.i(KakuUi.FONT_WEIGHT), false)

        fun walk(v: View)
        {
            if (KakuViews.NO_SKIN == v.tag) return
            when (v)
            {
                is Button ->
                {
                    v.setTextColor(text)
                    v.typeface = face
                    val g = GradientDrawable()
                    g.setColor(bg)
                    g.setStroke(maxOf(1, dp(ctx, 1.5f).toInt()), KakuUi.i(KakuUi.C_ACCENT))
                    g.cornerRadius = dp(ctx, 50f)
                    v.background = g
                    v.isAllCaps = false
                }
                is TextView ->
                {
                    val role = v.getTag(R.id.kaku_skin_role) as? String
                    v.setTextColor(if (role == "link") accent else text)
                    v.setLinkTextColor(accent)
                    v.typeface = face
                }
                is ProgressBar ->
                {
                    v.progressTintList = ColorStateList.valueOf(accent)
                    v.indeterminateTintList = ColorStateList.valueOf(accent)
                    v.progressBackgroundTintList = ColorStateList.valueOf(KakuUi.i(KakuUi.C_TEXT2))
                }
            }
            val role = v.getTag(R.id.kaku_skin_role) as? String
            val bgd = v.background
            if (role == "rule") v.setBackgroundColor(text)
            else if (bgd is ColorDrawable && v !is Button) v.setBackgroundColor(if (Color.alpha(bgd.color) == 0) Color.TRANSPARENT else bg)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /** Scaled interface text size, for code that sets sizes itself. */
    @JvmStatic
    fun uiTextPx(context: Context, sp: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * KakuUi.i(KakuUi.FONT_SCALE) / 100f,
                    context.resources.displayMetrics)
}
