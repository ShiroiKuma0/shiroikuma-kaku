package ca.fuwafuwa.kaku.Windows

import shiroikuma.kaku.KakuSkin
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ca.fuwafuwa.kaku.LangUtils
import ca.fuwafuwa.kaku.Ocr.BoxParams
import ca.fuwafuwa.kaku.R
import ca.fuwafuwa.kaku.Windows.Data.ISquareChar
import ca.fuwafuwa.kaku.Windows.Data.SquareCharOcr
import ca.fuwafuwa.kaku.dpToPx

enum class ChoiceResultType
{
    EDIT,
    DELETE,
    SWAP,
    NONE
}

class KanjiChoiceWindow(context: Context, windowCoordinator: WindowCoordinator) : Window(context, windowCoordinator, R.layout.window_kanji_choice)
{
    private val choiceWindow = window.findViewById<RelativeLayout>(R.id.kanji_choice_window)!!
    private val currentKanjiViews = mutableListOf<View>()

    private lateinit var mKanjiBoxParams : BoxParams

    private var drawnOnTop = false

    /** Where the swipe began (raw screen coordinates): its direction, not where it ends, decides. */
    private var mStartX = 0f
    private var mStartY = 0f

    /**
     * Set while the window stays open after the swipe ended off every candidate: the next tap picks
     * the candidate under it, or closes the window when it lands anywhere else.
     */
    private var pendingChoice: ((Pair<ChoiceResultType, String>) -> Unit)? = null

    /**
     * KanjiChoiceWindow does not need to reInit layout as its getDefaultParams() are all relative. Re-initing will cause bugs.
     */
    override fun reInit(options: Window.ReinitOptions)
    {
        options.reinitViewLayout = false
        super.reInit(options)
    }

    fun onSquareScrollStart(squareChar: ISquareChar, kanjiBoxParams: BoxParams, startX: Float, startY: Float)
    {
        mStartX = startX
        mStartY = startY
        pendingChoice = null
        if (squareChar !is SquareCharOcr)
        {
            show()

            mKanjiBoxParams = kanjiBoxParams
            mKanjiBoxParams.y -= statusBarHeight

            return
        }

        val topRectHeight = kanjiBoxParams.y - statusBarHeight
        val bottomRectHeight = realDisplaySize.y - kanjiBoxParams.y - kanjiBoxParams.height - (realDisplaySize.y - viewHeight - statusBarHeight)

        if (bottomRectHeight > topRectHeight)
        {
            drawnOnTop = false
            drawOnBottom(squareChar, kanjiBoxParams, calculateBounds(kanjiBoxParams, topRectHeight, bottomRectHeight))
        }
        else
        {
            drawnOnTop = true
            drawOnTop(squareChar, kanjiBoxParams, calculateBounds(kanjiBoxParams, topRectHeight, bottomRectHeight))
        }

        mKanjiBoxParams = kanjiBoxParams
        mKanjiBoxParams.y -= statusBarHeight

        show()
    }

    /** The icon shown in the character's cell while swiping: edit / delete for an upward swipe, else swap. */
    fun onSquareScroll(e: MotionEvent) : Int
    {
        return when (directionOf(e))
        {
            ChoiceResultType.EDIT -> R.drawable.icon_edit
            ChoiceResultType.DELETE -> R.drawable.icon_delete
            else -> R.drawable.icon_swap
        }
    }

    /**
     * The swipe ended. A clear upward swipe edits (up-left) or deletes (up-right) at once. Anything
     * else — a swipe down, even a brief flick, or barely any movement — leaves the candidates open,
     * answered NONE now: releasing never picks one. The choice comes by a tap, through
     * [onLaterChoice] — a candidate (or the character's own image, which restores the original), or
     * anywhere else to close.
     */
    fun onSquareScrollEnd(e: MotionEvent, onLaterChoice: (Pair<ChoiceResultType, String>) -> Unit) : Pair<ChoiceResultType, String>
    {
        val type = directionOf(e)
        if (type == ChoiceResultType.NONE && currentKanjiViews.any { candidateOf(it) != null })
        {
            pendingChoice = onLaterChoice
            return Pair(ChoiceResultType.NONE, "")
        }

        removeKanjiViews()
        hide()

        return Pair(type, "")
    }

    /** While the candidates wait for a tap: a candidate is picked, a tap anywhere else closes. */
    override fun onSingleTapUp(e: MotionEvent): Boolean
    {
        val callback = pendingChoice ?: return false
        var chosen = ""
        for (kanjiView in currentKanjiViews)
        {
            if (checkForSelection(kanjiView, e)) candidateOf(kanjiView)?.let { chosen = it }
        }
        pendingChoice = null
        removeKanjiViews()
        hide()
        callback(if (chosen != "") Pair(ChoiceResultType.SWAP, chosen) else Pair(ChoiceResultType.NONE, ""))
        return true
    }

    /**
     * What the swipe means, from its direction: upward by more than half a character is edit (to
     * the left) or delete (to the right); everything else opens the candidates.
     */
    private fun directionOf(e: MotionEvent) : ChoiceResultType
    {
        val dx = e.rawX - mStartX
        val dy = e.rawY - mStartY
        val threshold = maxOf(mKanjiBoxParams.height / 2, dpToPx(context, 16))
        if (dy < -threshold && Math.abs(dy) > Math.abs(dx) / 3)
        {
            return if (dx < 0) ChoiceResultType.EDIT else ChoiceResultType.DELETE
        }
        return ChoiceResultType.NONE
    }


    /**
     * The character a tile stands for: a candidate's text, or — for the character's own image —
     * the character as originally recognised, so tapping the image undoes an earlier swap.
     */
    private fun candidateOf(view: View): String?
    {
        return when
        {
            view is TextView -> view.text.toString()
            view.getTag(R.id.kaku_choice_original) is String -> view.getTag(R.id.kaku_choice_original) as String
            else -> null
        }
    }

    private fun checkForSelection(kanjiView: View, e: MotionEvent): Boolean
    {
        var pos = IntArray(2)
        kanjiView.getLocationOnScreen(pos)

        return pos[0] < e.rawX && e.rawX < pos[0] + kanjiView.width &&
               pos[1] < e.rawY && e.rawY < pos[1] + kanjiView.height
    }

    private fun removeKanjiViews()
    {
        for (k in currentKanjiViews)
        {
            choiceWindow.removeView(k)
        }

        currentKanjiViews.clear()
    }

    private fun calculateBounds(kanjiBoxParams: BoxParams, topRectHeight: Int, bottomRectHeight: Int) : BoxParams
    {
        val midPoint = kanjiBoxParams.x + (kanjiBoxParams.width / 2)
        var maxWidth = dpToPx(context, 400)
        var xPos = 0

        if (realDisplaySize.x > maxWidth)
        {
            xPos = midPoint - (maxWidth / 2)
            if (xPos < 0)
            {
                xPos = 0
            }
            else if (xPos + maxWidth > realDisplaySize.x)
            {
                xPos = realDisplaySize.x - maxWidth
            }
        }

        maxWidth = minOf(realDisplaySize.x, maxWidth)

        if (topRectHeight > bottomRectHeight)
        {
            return BoxParams(xPos, 0, maxWidth, topRectHeight)
        }
        else
        {
            return BoxParams(xPos, kanjiBoxParams.y + kanjiBoxParams.height - statusBarHeight, maxWidth, bottomRectHeight)
        }
    }

    private fun drawOnBottom(squareChar: SquareCharOcr, kanjiBoxParams: BoxParams, choiceParams: BoxParams)
    {
        // Twice the character, but no bigger than 72 dp now that the characters themselves are large.
        val kanjiHeight = minOf(kanjiBoxParams.height * 2, dpToPx(context, 72))
        val kanjiWidth = minOf(kanjiBoxParams.width * 2, dpToPx(context, 72))

        val outerPadding = dpToPx(context, 10)
        val startHeight = choiceParams.y + outerPadding

        val drawableWidth = choiceParams.width - outerPadding
        val minPadding = dpToPx(context, 5)
        val numColumns = minOf(calculateNumColumns(drawableWidth, kanjiWidth, minPadding), squareChar.allChoices.size + 1)
        val outerSpacing = (choiceParams.width - (kanjiWidth + minPadding * 2) * numColumns) / 2
        val innerSpacing = minPadding

        var currColumn = 0
        var currWidth = choiceParams.x + outerSpacing + innerSpacing
        var currHeight = startHeight

        drawKanjiImage(squareChar, currWidth, currHeight, kanjiWidth, kanjiHeight)
        currWidth += kanjiWidth + innerSpacing
        currColumn++

        for (choice in squareChar.allChoices)
        {
            if (currColumn >= numColumns)
            {
                currHeight += kanjiHeight + innerSpacing
                currWidth = choiceParams.x + outerSpacing + innerSpacing
                currColumn = 0
            }

            drawKanjiText(choice.first, currWidth, currHeight, kanjiWidth, kanjiHeight)
            currWidth += kanjiWidth + innerSpacing
            currColumn++
        }
    }

    private fun drawOnTop(squareChar: SquareCharOcr, kanjiBoxParams: BoxParams, choiceParams: BoxParams)
    {
        // Twice the character, but no bigger than 72 dp now that the characters themselves are large.
        val kanjiHeight = minOf(kanjiBoxParams.height * 2, dpToPx(context, 72))
        val kanjiWidth = minOf(kanjiBoxParams.width * 2, dpToPx(context, 72))

        val outerPadding = dpToPx(context, 10)
        val startHeight = kanjiBoxParams.y - statusBarHeight - kanjiHeight - outerPadding

        val drawableWidth = choiceParams.width - outerPadding
        val minPadding = dpToPx(context, 5)
        val numColumns = minOf(calculateNumColumns(drawableWidth, kanjiWidth, minPadding), squareChar.allChoices.size + 1)
        val outerSpacing = (choiceParams.width - (kanjiWidth + minPadding * 2) * numColumns) / 2
        val innerSpacing = minPadding

        var currColumn = 0
        var currWidth = choiceParams.x + outerSpacing + innerSpacing
        var currHeight = startHeight

        drawKanjiImage(squareChar, currWidth, currHeight, kanjiWidth, kanjiHeight)
        currWidth += kanjiWidth + innerSpacing
        currColumn++

        for (choice in squareChar.allChoices)
        {
            if (currColumn >= numColumns)
            {
                currHeight -= kanjiHeight + innerSpacing
                currWidth = choiceParams.x + outerSpacing + innerSpacing
                currColumn = 0
            }

            drawKanjiText(choice.first, currWidth, currHeight, kanjiWidth, kanjiHeight)
            currWidth += kanjiWidth + innerSpacing
            currColumn++
        }
    }

    private fun drawKanjiText(kanji: String, x: Int, y: Int, kanjiWidth: Int, kanjiHeight: Int)
    {
        val tv = TextView(context)
        tv.text = kanji
        tv.gravity = Gravity.CENTER
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, (kanjiWidth / 1.5).toFloat())

        tv.setTextColor(KakuSkin.choiceTextColor(kanji[0]))
        tv.typeface = KakuSkin.charTypeface(context)
        tv.background = KakuSkin.choiceCell(context, false)
        tv.width = kanjiWidth
        tv.height = kanjiHeight
        tv.x = x.toFloat()
        tv.y = y.toFloat()
        choiceWindow.addView(tv)
        currentKanjiViews.add(tv)
    }

    private fun drawKanjiImage(squareChar: SquareCharOcr, x: Int, y: Int, kanjiWidth: Int, kanjiHeight: Int)
    {
        // Image nonsense
        val pos = squareChar.bitmapPos
        val dp10 = dpToPx(context, 10)
        val orig = squareChar.displayData.bitmap
        var width = pos[2] - pos[0]
        var height = pos[3] - pos[1]
        width = if (width <= 0) 1 else width
        height = if (height <= 0) 1 else height
        val bitmapChar = Bitmap.createBitmap(orig, pos[0], pos[1], width, height)
        val charImage = ImageView(context)
        charImage.setPadding(dp10, dp10, dp10, dp10)
        charImage.layoutParams = LinearLayout.LayoutParams(kanjiWidth, kanjiHeight)
        charImage.x = x.toFloat()
        charImage.y = y.toFloat()
        charImage.scaleType = ImageView.ScaleType.FIT_CENTER
        charImage.cropToPadding = true
        charImage.setImageBitmap(bitmapChar)
        charImage.background = KakuSkin.choiceImage(context)
        if (squareChar.originalChar.isNotEmpty()) charImage.setTag(R.id.kaku_choice_original, squareChar.originalChar)
        choiceWindow.addView(charImage)
        currentKanjiViews.add(charImage)
    }

    private fun calculateNumColumns(drawableWidth: Int, columnWidth: Int, minPadding: Int) : Int
    {
        var count = 0
        var width = 0
        val columnAndPadding = columnWidth + (minPadding * 2)

        while ((width + columnAndPadding) < drawableWidth)
        {
            width += columnAndPadding
            count++
        }

        return count
    }


    override fun onTouch(e: MotionEvent): Boolean
    {
        return false
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean
    {
        return false
    }

    override fun onResize(e: MotionEvent): Boolean
    {
        return false
    }

    override fun getDefaultParams(): WindowManager.LayoutParams
    {
        val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT > 25) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
        params.x = 0
        params.y = 0
        return params
    }
}