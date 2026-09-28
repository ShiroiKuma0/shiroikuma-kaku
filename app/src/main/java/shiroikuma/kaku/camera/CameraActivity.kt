package shiroikuma.kaku.camera

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import java.util.concurrent.Executors
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ca.fuwafuwa.kaku.EXTRA_PROJECTION_RESULT_CODE
import ca.fuwafuwa.kaku.EXTRA_PROJECTION_RESULT_INTENT
import ca.fuwafuwa.kaku.KAKU_PREF_FILE
import ca.fuwafuwa.kaku.KAKU_PREF_SHOW_HIDE
import ca.fuwafuwa.kaku.MainService
import ca.fuwafuwa.kaku.R
import ca.fuwafuwa.kaku.setupKakuDatabasesAndFiles
import ca.fuwafuwa.kaku.startKakuService
import shiroikuma.kaku.KakuToast
import shiroikuma.kaku.KakuUi
import shiroikuma.kaku.KakuViews
import kotlin.math.abs

/**
 * 白い熊 画 カメラ — OCR through the camera. A full-screen camera picture under the ordinary capture
 * box: the box reads the screen as it reads any app, so recognition, the result windows, the
 * candidates and the dictionary are exactly the ones used everywhere else.
 *
 * Controls: Freeze / Unfreeze holds the current frame still (the camera is released meanwhile);
 * tap to focus; pinch to zoom; torch; Live, which re-reads the box whenever the picture has
 * settled (the small instant popup, beside the box); close.
 *
 * Reached five ways: its own launcher icon (activity-alias, switchable on the UI page), the app
 * shortcut, `shiroikuma.kaku.action.CAMERA` for 自由作業盤 / Tasker, the start screen's camera button,
 * and the notification. It starts the capture service itself when it is not running (Android's one
 * screen-capture prompt), and shows the capture box if it was hidden.
 */
class CameraActivity : AppCompatActivity()
{
    private lateinit var root: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var frozenView: ImageView
    private lateinit var freezeButton: Button
    private lateinit var liveButton: Button
    private lateinit var torchButton: Button

    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null
    private var frozen = false
    private var torch = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastSample: IntArray? = null
    private var stableMs = 0L
    private var changedSinceRead = true

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCameraFlow()
        else
        {
            KakuToast.show(this, getString(R.string.camera_permission_needed), true)
            finish()
        }
    }

    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null)
        {
            startKakuService(this, Intent(this, MainService::class.java)
                    .putExtra(EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                    .putExtra(EXTRA_PROJECTION_RESULT_INTENT, result.data))
            bindCamera()
        }
        else
        {
            KakuToast.show(this, getString(R.string.capture_consent_declined), true)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        buildViews()
        setupKakuDatabasesAndFiles(this)

        if (!Settings.canDrawOverlays(this))
        {
            KakuToast.show(this, getString(R.string.camera_overlay_needed), true)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        else
        {
            startCameraFlow()
        }
    }

    /** The capture service first (one consent prompt if it is not running), then the camera. */
    private fun startCameraFlow()
    {
        if (!MainService.IsRunning())
        {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureConsent.launch(manager.createScreenCaptureIntent())
            return
        }
        // Running but hidden (power-saving): show the capture box again, as the notification's tap does.
        val prefs = getSharedPreferences(KAKU_PREF_FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KAKU_PREF_SHOW_HIDE, true))
        {
            prefs.edit().putBoolean(KAKU_PREF_SHOW_HIDE, true).apply()
            startKakuService(this, Intent(this, MainService::class.java))
        }
        bindCamera()
    }

    override fun onStart()
    {
        super.onStart()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, handler)
    }

    override fun onStop()
    {
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        super.onStop()
    }

    override fun onDestroy()
    {
        handler.removeCallbacksAndMessages(null)
        provider?.unbindAll()
        analyzer.shutdown()
        super.onDestroy()
    }

    // ---- camera --------------------------------------------------------------------------------

    private fun bindCamera()
    {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try
            {
                val p = future.get()
                provider = p
                p.unbindAll()
                val rotation = displayRotation()
                val useCase = Preview.Builder().setTargetRotation(rotation).build().also { it.surfaceProvider = preview.surfaceProvider }
                previewUseCase = useCase
                // A small, latest-frame-only stream for live mode's "has the picture settled?" test.
                val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder()
                                .setResolutionStrategy(ResolutionStrategy(Size(320, 240),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(rotation)
                        .build()
                analysisUseCase = analysis
                analysis.setAnalyzer(analyzer) { image -> analyze(image) }
                camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCase, analysis)
                camera?.cameraControl?.enableTorch(torch)
            }
            catch (e: Exception)
            {
                KakuToast.show(this, getString(R.string.camera_unavailable, e.message ?: e.javaClass.simpleName), true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** The display's rotation now (the activity follows all four; see the manifest). */
    private fun displayRotation(): Int = ContextCompat.getDisplayOrDefault(this).rotation

    /**
     * The activity handles rotation itself (configChanges), so CameraX must be told: the preview
     * and the analysis stream follow the display, and the capture box re-fits (the service's
     * DisplayListener). A frozen frame keeps its proportions — it is shown fitted, never stretched.
     */
    override fun onConfigurationChanged(newConfig: Configuration)
    {
        super.onConfigurationChanged(newConfig)
        followRotation()
    }

    private fun followRotation()
    {
        val rotation = displayRotation()
        if (previewUseCase?.targetRotation == rotation) return
        previewUseCase?.targetRotation = rotation
        analysisUseCase?.targetRotation = rotation
        lastSample = null
    }

    /** A 180° turn changes no configuration, so the display itself is watched as well. */
    private val displayListener = object : DisplayManager.DisplayListener
    {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) = followRotation()
    }

    private fun freeze()
    {
        val still = preview.bitmap ?: return
        frozenView.setImageBitmap(still)
        frozenView.visibility = View.VISIBLE
        provider?.unbindAll()
        camera = null
        frozen = true
        styleButtons()
    }

    private fun unfreeze()
    {
        frozenView.visibility = View.GONE
        frozenView.setImageBitmap(null)
        frozen = false
        lastSample = null
        changedSinceRead = true
        bindCamera()
        styleButtons()
    }

    // ---- live mode ------------------------------------------------------------------------------

    private val analyzer = Executors.newSingleThreadExecutor()
    @Volatile private var lastAnalyzedMs = 0L

    /**
     * Four times a second, from the small analysis stream: a 32-column grid of mean brightness
     * (the Y plane, no colour conversion), handed to [onSample] on the main thread.
     */
    private fun analyze(image: ImageProxy)
    {
        try
        {
            val now = System.currentTimeMillis()
            if (!KakuUi.b(KakuUi.CAM_LIVE) || now - lastAnalyzedMs < TICK_MS) return
            lastAnalyzedMs = now
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height
            val cols = 32
            val rows = maxOf(1, h * cols / maxOf(1, w))
            val sample = IntArray(cols * rows)
            for (r in 0 until rows)
            {
                val y = r * h / rows + h / rows / 2
                for (c in 0 until cols)
                {
                    val x = c * w / cols + w / cols / 2
                    sample[r * cols + c] = buf.get(y * rowStride + x * pixelStride).toInt() and 0xFF
                }
            }
            handler.post { onSample(sample) }
        }
        catch (ignored: Exception)
        {
            // a frame that cannot be read is simply skipped
        }
        finally
        {
            image.close()
        }
    }

    /**
     * Once the picture has stayed still for the settle time (UI page) and has changed since the
     * last read, the capture box reads it (MainService.recognizeInCaptureBox).
     */
    private fun onSample(sample: IntArray)
    {
        if (!KakuUi.b(KakuUi.CAM_LIVE) || frozen || camera == null) return
        val prev = lastSample
        lastSample = sample
        if (prev == null || prev.size != sample.size)
        {
            stableMs = 0
            changedSinceRead = true
            return
        }
        var diff = 0L
        for (i in sample.indices) diff += abs(sample[i] - prev[i])
        if (diff.toDouble() / sample.size > STILL_THRESHOLD)
        {
            stableMs = 0
            changedSinceRead = true
            return
        }
        stableMs += TICK_MS
        if (changedSinceRead && stableMs >= KakuUi.i(KakuUi.CAM_LIVE_SETTLE))
        {
            changedSinceRead = false
            MainService.recognizeInCaptureBox()
        }
    }

    // ---- views ----------------------------------------------------------------------------------

    private fun buildViews()
    {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // getBitmap() for Freeze and Live
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        frozenView = ImageView(this).apply {
            // The still is exactly what the preview showed (PreviewView.getBitmap, view-sized): fitted
            // rather than stretched, so it can never distort, even after a rotation while frozen.
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(frozenView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Tap to focus, pinch to zoom.
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener()
        {
            override fun onScale(detector: ScaleGestureDetector): Boolean
            {
                val cam = camera ?: return false
                val zoom = cam.cameraInfo.zoomState.value ?: return false
                cam.cameraControl.setZoomRatio((zoom.zoomRatio * detector.scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
                return true
            }
        })
        preview.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !scale.isInProgress && e.eventTime - e.downTime < 300)
            {
                val point = preview.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                v.performClick()
            }
            true
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val close = pill(getString(R.string.camera_close)) { finish() }
        torchButton = pill("") {
            torch = !torch
            camera?.cameraControl?.enableTorch(torch)
            styleButtons()
        }
        top.addView(close)
        top.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        top.addView(torchButton)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        liveButton = pill("") {
            KakuUi.set(KakuUi.CAM_LIVE, !KakuUi.b(KakuUi.CAM_LIVE))
            changedSinceRead = true
            styleButtons()
        }
        freezeButton = pill("") { if (frozen) unfreeze() else freeze() }
        freezeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        bottom.addView(liveButton)
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        bottom.addView(freezeButton)
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        bottom.addView(View(this), LinearLayout.LayoutParams(liveButtonWidthHint(), 0))

        val controls = FrameLayout(this)
        controls.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        controls.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val pad = dp(14)
        controls.setPadding(pad, pad, pad, pad)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        styleButtons()
    }

    /** Balances the bottom row so Freeze sits in the middle. */
    private fun liveButtonWidthHint(): Int = dp(96)

    private fun pill(label: String, onClick: () -> Unit): Button =
            KakuViews.pill(this, label) { onClick() }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minWidth = dp(96)
                setPadding(dp(18), dp(10), dp(18), dp(10))
            }

    private fun styleButtons()
    {
        freezeButton.text = getString(if (frozen) R.string.camera_unfreeze else R.string.camera_freeze)
        torchButton.text = getString(if (torch) R.string.camera_torch_on else R.string.camera_torch_off)
        liveButton.text = getString(if (KakuUi.b(KakuUi.CAM_LIVE)) R.string.camera_live_on else R.string.camera_live_off)
        torchButton.isEnabled = !frozen
        torchButton.alpha = if (frozen) 0.5f else 1f
        liveButton.alpha = if (frozen) 0.5f else 1f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object
    {
        private const val TICK_MS = 250L
        /** Mean grey-level change (0–255) below which two thumbnails count as the same picture. */
        private const val STILL_THRESHOLD = 6.0

        const val ACTION_CAMERA = "shiroikuma.kaku.action.CAMERA"
        /** The launcher icon of 白い熊 画 カメラ, an activity-alias switched on the UI page. */
        const val LAUNCHER_ALIAS = "shiroikuma.kaku.camera.CameraLauncherAlias"

        @JvmStatic
        fun open(context: Context)
        {
            context.startActivity(Intent(context, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Show or hide the separate launcher icon, as the UI page says. */
        @JvmStatic
        fun applyLauncherIcon(context: Context)
        {
            val state = if (KakuUi.b(KakuUi.CAM_LAUNCHER)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val component = ComponentName(context.packageName, LAUNCHER_ALIAS)
            try
            {
                if (context.packageManager.getComponentEnabledSetting(component) != state)
                {
                    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
            }
            catch (ignored: Exception)
            {
                // an alias that cannot be toggled keeps its manifest state
            }
        }
    }
}
