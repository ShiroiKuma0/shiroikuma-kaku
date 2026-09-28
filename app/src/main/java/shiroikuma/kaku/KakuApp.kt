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
        // Builds up to 0.1.0+019 copied upstream's 2019 JMdict out of the APK into files/; nothing reads it
        // any more (lookups use the imported Yomitan dictionaries), so the 22 MB copy goes.
        for (n in listOf(ca.fuwafuwa.kaku.JMDICT_DATABASE_NAME, ca.fuwafuwa.kaku.JMDICT_DATABASE_NAME + "-journal"))
        {
            java.io.File(filesDir, n).delete()
        }
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
