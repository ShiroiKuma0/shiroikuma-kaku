package shiroikuma.kaku

import android.app.Activity
import android.app.Application
import android.os.Bundle
import shiroikuma.kaku.camera.CameraActivity

/**
 * Loads the 白い熊 画 UI settings before anything draws, and paints every activity of the app in
 * them (the UI page paints itself).
 */
class KakuApp : Application()
{
    override fun onCreate()
    {
        super.onCreate()
        KakuUi.init(this)
        CameraActivity.applyLauncherIcon(this)
        // A dictionary import the process died in the middle of is removed, never half-used.
        Thread { try { shiroikuma.kaku.dict.DictDb.get(this).deleteIncomplete() } catch (ignored: Exception) {} }.start()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks
        {
            override fun onActivityResumed(activity: Activity)
            {
                if (activity !is KakuUiActivity && activity !is ProjectionConsentActivity && activity !is CameraActivity)
                {
                    KakuSkin.applyToActivity(activity)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
