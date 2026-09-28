package shiroikuma.kaku

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The result window's dictionary view: a WebView showing [shiroikuma.kaku.dict.YomitanHtml] pages.
 *
 * - **Height:** as tall as its content, at most what the window leaves it (the page reports its
 *   height through `KakuHost.height`), so short results give a short window, long ones scroll.
 * - **Pinch to zoom:** two fingers scale the text (WebView text zoom, so it reflows to the window
 *   width rather than panning); the zoom is kept in [KakuUi.DICT_ZOOM] for every later lookup.
 * - **Offline:** nothing is navigated — every link click is swallowed; local font files load.
 */
@SuppressLint("SetJavaScriptEnabled")
class DictWebView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs)
{
    private val main = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private var contentPx = 0
    private var zoom = KakuUi.i(KakuUi.DICT_ZOOM).toFloat()
    private var pinching = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener()
    {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean
        {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean
        {
            zoom = (zoom * detector.scaleFactor).coerceIn(ZOOM_MIN.toFloat(), ZOOM_MAX.toFloat())
            val z = Math.round(zoom)
            if (z != settings.textZoom) settings.textZoom = z
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector)
        {
            KakuUi.setQuietly(KakuUi.DICT_ZOOM, settings.textZoom)
        }
    })

    init
    {
        setBackgroundColor(Color.TRANSPARENT)
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        with(settings)
        {
            javaScriptEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            blockNetworkLoads = true
            textZoom = zoom.toInt()
        }
        webViewClient = object : WebViewClient()
        {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        addJavascriptInterface(object
        {
            @JavascriptInterface
            fun height(cssPx: Float)
            {
                main.post {
                    val px = Math.ceil((cssPx * density).toDouble()).toInt()
                    if (px != contentPx)
                    {
                        contentPx = px
                        requestLayout()
                    }
                }
            }
        }, "KakuHost")
    }

    /** Show a page; the zoom follows the setting (the UI page's slider or an earlier pinch). */
    fun showPage(html: String)
    {
        zoom = KakuUi.i(KakuUi.DICT_ZOOM).toFloat()
        settings.textZoom = zoom.toInt()
        loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        scrollTo(0, 0)
    }

    fun clear()
    {
        contentPx = 0
        loadDataWithBaseURL("file:///android_asset/", "<html><body></body></html>", "text/html", "utf-8", null)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
    {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val h = when (mode)
        {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> minOf(contentPx, size)
            else -> contentPx
        }
        setMeasuredDimension(measuredWidth, h)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean
    {
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1 || scaleDetector.isInProgress)
        {
            if (!pinching)
            {
                // Hand the WebView a cancel so a scroll it began with the first finger stops.
                pinching = true
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        if (pinching)
        {
            // The rest of a pinch (one finger lifted first) is not a scroll.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
            return true
        }
        return super.onTouchEvent(event)
    }

    companion object
    {
        const val ZOOM_MIN = 50
        const val ZOOM_MAX = 300
    }
}
