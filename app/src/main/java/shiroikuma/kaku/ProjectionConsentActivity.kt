package shiroikuma.kaku

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ca.fuwafuwa.kaku.EXTRA_PROJECTION_RESULT_CODE
import ca.fuwafuwa.kaku.EXTRA_PROJECTION_RESULT_INTENT
import ca.fuwafuwa.kaku.MainService
import ca.fuwafuwa.kaku.R
import ca.fuwafuwa.kaku.startKakuService

/**
 * Asks for a fresh screen-capture consent while the service is running. From Android 14 on a
 * consent can start only one MediaProjection, so after the projection stopped (screen off, or
 * stopped by the system) the service cannot reuse the one MainActivity obtained. The result is
 * handed to MainService, which starts the new projection with it.
 */
class ProjectionConsentActivity : ComponentActivity()
{
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null)
        {
            startKakuService(this, Intent(this, MainService::class.java)
                    .putExtra(EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                    .putExtra(EXTRA_PROJECTION_RESULT_INTENT, result.data))
        }
        else
        {
            Toast.makeText(this, getString(R.string.capture_consent_declined), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null)
        {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            consent.launch(manager.createScreenCaptureIntent())
        }
    }

    companion object
    {
        @JvmStatic
        fun request(context: Context)
        {
            context.startActivity(Intent(context, ProjectionConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}
