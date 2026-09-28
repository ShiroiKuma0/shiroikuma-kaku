package shiroikuma.kaku

import android.app.Activity
import android.app.Application
import android.os.Bundle

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
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks
        {
            override fun onActivityResumed(activity: Activity)
            {
                if (activity !is KakuUiActivity && activity !is ProjectionConsentActivity)
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
