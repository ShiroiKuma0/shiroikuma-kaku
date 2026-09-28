package ca.fuwafuwa.kaku

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import shiroikuma.kaku.KakuToast
import shiroikuma.kaku.applySystemBarPadding


class MainActivity : AppCompatActivity()
{
    private var mIsActivityVisible = false

    private lateinit var mPrefs : SharedPreferences
    private lateinit var mStartKakuIntent: Intent

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        mPrefs = getSharedPreferences(KAKU_PREF_FILE, Context.MODE_PRIVATE)

        if (isFirstLaunch())
        {
            startActivity(Intent(this, TutorialActivity::class.java))
            finish()
        }
        else {
            supportActionBar?.hide()
            setContentView(R.layout.activity_main)
            applySystemBarPadding(this)

            setupKakuDatabasesAndFiles(this)
        }
    }

    override fun onStart()
    {
        super.onStart()

        checkNotificationPermission()
        checkDrawOnTopPermissions()
        checkScreenRecordPermissions()
    }

    override fun onPause()
    {
        super.onPause()
        Log.d(TAG, "ACTIVITY INVISIBLE")
        mIsActivityVisible = false
    }

    override fun onResume()
    {
        super.onResume()
        Log.d(TAG, "ACTIVITY VISIBLE")
        mIsActivityVisible = true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        Log.d(TAG, "onActivityResult")

        val relaunchAppText = getString(R.string.relaunch_after_permission)

        if (requestCode == REQUEST_DRAW_ON_TOP)
        {
            Log.d(TAG, "Recieved ACTION_MANAGE_OVERLAY_PERMISSION Intent")

            if (resultCode != Activity.RESULT_OK)
            {
                KakuToast.show(this, "Check Permission: Draw on Other Apps\n$relaunchAppText", true)
                finish()
            }

            return
        }

        if (requestCode == REQUEST_SCREENSHOT)
        {
            Log.d(TAG, "Recieved REQUEST_SCREENSHOT Intent")

            if (resultCode != Activity.RESULT_OK)
            {
                KakuToast.show(this, "Check Permission: Record Screen\n$relaunchAppText", true)
                finish()
            }

            mStartKakuIntent = Intent(this, MainService::class.java)
                    .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_PROJECTION_RESULT_INTENT, data)
            return
        }
    }

    fun startKaku(startFragment: MainStartFragment)
    {
        if (MainService.IsRunning())
        {
            return
        }

        if (!mIsActivityVisible)
        {
            return
        }

        if (::mStartKakuIntent.isInitialized)
        {
            startFragment.onKakuLoadStart()

            val totalDuration = 2000
            object : CountDownTimer(totalDuration.toLong(), 10)
            {
                override fun onFinish()
                {
                    startFragment.onKakuLoaded()
                    startKakuService(this@MainActivity, mStartKakuIntent)
                }

                override fun onTick(millisUntilFinished: Long)
                {
                }
            }.start()
        }
        else {
            KakuToast.show(this, getString(R.string.unable_to_start_service), true)
        }
    }

    private fun checkDrawOnTopPermissions()
    {
        var checkPermissions = "Check \"Draw on Top of Other Apps\" permission"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            if (!Settings.canDrawOverlays(this))
            {
                Log.d(TAG, "Sending ACTION_MANAGE_OVERLAY_PERMISSION Intent")
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQUEST_DRAW_ON_TOP)
            }
        }
        else
        {
            KakuToast.show(this, getString(R.string.manually_check_permission, checkPermissions), true)
        }
    }

    private fun checkScreenRecordPermissions()
    {
        Log.d(TAG, "Sending REQUEST_SCREENSHOT Intent")
        val mediaProjectionManager: MediaProjectionManager? = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager!!.createScreenCaptureIntent(), REQUEST_SCREENSHOT)
    }

    /**
     * Android 13+: the foreground service's notification (show / hide, instant mode, image filter,
     * shutdown) is only shown once the user allows notifications.
     */
    private fun checkNotificationPermission()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    private fun isFirstLaunch() : Boolean
    {
        return mPrefs.getBoolean(KAKU_PREF_FIRST_LAUNCH, true)
    }

    companion object
    {
        private val TAG = MainActivity::class.java.name
    }
}
