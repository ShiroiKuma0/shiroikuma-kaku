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
import shiroikuma.kaku.KakuViews
import shiroikuma.kaku.camera.CameraActivity
import shiroikuma.kaku.dict.DictDb
import shiroikuma.kaku.ocr.MangaOcr
import shiroikuma.kaku.ocr.OcrImport
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

        rootView.findViewById<ImageView>(R.id.kaku_camera).setOnClickListener { CameraActivity.open(mainActivity) }

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
        rootView.findViewById<ImageView>(R.id.kaku_camera).setColorFilter(KakuUi.i(KakuUi.C_ACCENT))
        showFirstRunGuide()

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

    /**
     * 白い熊 画 ships no OCR data and no dictionary: until both are imported, the start screen says
     * so once per process and offers the way to 白い熊 画 UI, where the OCR and Dictionaries sections
     * show the download URLs and import the files.
     */
    private fun showFirstRunGuide()
    {
        if (sGuideShown) return
        val ctx = mainActivity.applicationContext
        val ocr = MangaOcr.installed(ctx) || OcrImport.tesseractFile(ctx).let { it.isFile && it.length() > 0 }
        val dict = try { DictDb.get(ctx).hasEnabledTerms() } catch (e: Exception) { false }
        if (ocr && dict) return
        sGuideShown = true
        val missing = StringBuilder()
        if (!ocr) missing.append(getString(R.string.first_run_ocr))
        if (!dict)
        {
            if (missing.isNotEmpty()) missing.append("\n\n")
            missing.append(getString(R.string.first_run_dict))
        }
        missing.append("\n\n").append(getString(R.string.first_run_where))
        KakuViews.showConfirm(mainActivity, getString(R.string.first_run_title), missing,
                getString(R.string.first_run_open)) { KakuUiActivity.open(mainActivity) }
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

    companion object
    {
        /** The first-run guide shows at most once per process. */
        private var sGuideShown = false
    }
}
