package ca.fuwafuwa.kaku

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import android.widget.ImageView
import shiroikuma.kaku.KakuFork
import shiroikuma.kaku.KakuUiActivity
import shiroikuma.kaku.KakuUi
import java.util.*


class MainStartFragment : Fragment()
{
    private lateinit var mainActivity : MainActivity
    private lateinit var rootView : View

    private lateinit var kakuLogo : TextView
    private lateinit var kakuTitle : TextView
    private lateinit var tutorialText : TextView
    private lateinit var githubText : TextView

    private lateinit var supportText : TextView
    private lateinit var progressBar : ProgressBar

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View?
    {
        mainActivity = activity as MainActivity

        rootView = inflater.inflate(R.layout.fragment_start, container, false)

        kakuLogo = rootView.findViewById(R.id.kaku_logo)
        kakuTitle = rootView.findViewById(R.id.kaku_title)
        tutorialText = rootView.findViewById(R.id.kaku_tutorial)
        githubText = rootView.findViewById(R.id.kaku_github)

        supportText = rootView.findViewById(R.id.support_text)
        progressBar = rootView.findViewById(R.id.progress_bar)

        tutorialText.setOnClickListener {
            startActivity(Intent(mainActivity, TutorialActivity::class.java))
        }

        githubText.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KakuFork.GITHUB)))
        }

        // The Settings cog opens the 白い熊 画 UI page — on a long-press (the family's gesture) and,
        // there being no upstream settings page, on a tap as well.
        val settings = rootView.findViewById<ImageView>(R.id.kaku_settings)
        settings.setOnClickListener { KakuUiActivity.open(mainActivity) }
        settings.setOnLongClickListener { KakuUiActivity.open(mainActivity); true }

        if (MainService.IsRunning())
        {
            onKakuLoaded()
        }

        // Size the texts from the fragment's own size (portrait, landscape, folded or unfolded).
        // Only a real change is applied, so the resulting layout pass does not loop.
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val h = rootView.height.toFloat()
            val w = rootView.width.toFloat()
            if (h <= 0f || w <= 0f) return@addOnGlobalLayoutListener

            val logoSize = minOf(h * 0.30f, w * 0.55f)
            val titleSize = logoSize / 5
            val textSize = maxOf(titleSize / 2, dpToPx(mainActivity, 14).toFloat())

            if (kakuLogo.textSize != logoSize)
            {
                kakuLogo.setTextSize(TypedValue.COMPLEX_UNIT_PX, logoSize)
                kakuTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize)
                tutorialText.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                githubText.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                supportText.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            }
        }

        return rootView
    }

    override fun onResume()
    {
        super.onResume()

        rootView.findViewById<ImageView>(R.id.kaku_settings).setColorFilter(KakuUi.i(KakuUi.C_ACCENT))

        if (!MainService.IsRunning())
        {
            onKakuLoadStart()
        }

        Timer().schedule(object : TimerTask()
        {
            override fun run()
            {
                mainActivity.runOnUiThread {
                    mainActivity.startKaku(this@MainStartFragment)
                }
            }
        }, 3000)
    }

    fun onKakuLoadStart()
    {
        progressBar.isIndeterminate = true
        progressBar.progress = 0
        supportText.text = getString(R.string.kaku_loading)
    }

    fun onKakuLoaded()
    {
        progressBar.isIndeterminate = false
        progressBar.progress = 100
        supportText.text = getString(R.string.kaku_running)
    }
}
