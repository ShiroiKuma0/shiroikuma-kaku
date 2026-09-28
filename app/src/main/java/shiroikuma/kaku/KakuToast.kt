package shiroikuma.kaku

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * The app's toast in the house look — dialog ink on the dialog ground, bordered and round, from the
 * 白い熊 画 UI settings — replacing the system's white pill everywhere.
 *
 * <p>Since Android 11 a toast with a custom view is shown only while the app is in the foreground,
 * and most of this app's messages come from the capture service while another app is on screen.
 * So the toast is drawn as a small untouchable overlay window (the app holds "draw over other
 * apps"), and falls back to a styled [Toast] only when that permission is missing.
 */
object KakuToast
{
    private const val SHORT_MS = 2000L
    private const val LONG_MS = 3500L

    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private var currentRemoval: Runnable? = null

    @JvmStatic
    @JvmOverloads
    fun show(context: Context, text: CharSequence, long: Boolean = false)
    {
        val app = context.applicationContext
        main.post { showNow(app, text, long) }
    }

    private fun showNow(app: Context, text: CharSequence, long: Boolean)
    {
        KakuUi.init(app)
        if (!Settings.canDrawOverlays(app))
        {
            val t = Toast(app)
            @Suppress("DEPRECATION")
            t.view = bubble(app, text)
            t.duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            t.show()
            return
        }

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dismiss(wm)

        val view = bubble(app, text)
        val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = (app.resources.displayMetrics.heightPixels * 0.10f).toInt()
        params.windowAnimations = android.R.style.Animation_Toast
        try
        {
            wm.addView(view, params)
        }
        catch (e: Exception)
        {
            return
        }
        current = view
        val removal = Runnable { dismiss(wm) }
        currentRemoval = removal
        main.postDelayed(removal, if (long) LONG_MS else SHORT_MS)
    }

    private fun dismiss(wm: WindowManager)
    {
        currentRemoval?.let { main.removeCallbacks(it) }
        currentRemoval = null
        val v = current ?: return
        current = null
        try
        {
            wm.removeView(v)
        }
        catch (ignored: Exception)
        {
            // already gone
        }
    }

    private fun bubble(context: Context, text: CharSequence): TextView
    {
        val d = context.resources.displayMetrics.density
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(KakuUi.i(KakuUi.C_DLG_TEXT))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.typeface = KakuFonts.at(context, KakuUi.s(KakuUi.FONT_FAMILY), KakuUi.i(KakuUi.FONT_WEIGHT), false)
        tv.gravity = Gravity.CENTER
        tv.setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        tv.maxWidth = (context.resources.displayMetrics.widthPixels * 0.85f).toInt()
        val bg = GradientDrawable()
        bg.setColor(KakuUi.i(KakuUi.C_DLG_BG))
        bg.setStroke(maxOf(1, (2 * d).toInt()), KakuUi.i(KakuUi.C_DLG_BORDER))
        bg.cornerRadius = 18 * d
        tv.background = bg
        return tv
    }
}
