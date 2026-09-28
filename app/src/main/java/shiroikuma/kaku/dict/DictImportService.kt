package shiroikuma.kaku.dict

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ca.fuwafuwa.kaku.R
import shiroikuma.kaku.KakuToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Imports picked dictionary zips one after another, as a dataSync foreground service with a progress
 * notification — JMdict is half a million rows and Jitendex more, minutes of work that must survive
 * the UI page being closed. The UI page follows along through [listeners] / [current].
 */
class DictImportService : Service()
{
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        try
        {
            val n = notification(getString(R.string.dict_import_starting), 0, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, n)
        }
        catch (e: Exception)
        {
            KakuToast.show(this, getString(R.string.dict_import_failed, e.message ?: e.javaClass.simpleName), true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val uris = intent?.getParcelableArrayListExtra<Uri>(EXTRA_URIS) ?: arrayListOf()
        executor.execute { run(uris, startId) }
        return START_NOT_STICKY
    }

    private fun run(uris: List<Uri>, startId: Int)
    {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiroikuma.kaku:dict-import").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
        val report = StringBuilder()
        try
        {
            for (uri in uris)
            {
                var lastNotify = 0L
                try
                {
                    val r = DictImport.import(this, uri) { p ->
                        current = p
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 500)
                        {
                            lastNotify = now
                            notifyProgress(p)
                            for (l in listeners) main.post { l.onProgress(p) }
                        }
                    }
                    report.append(getString(R.string.dict_import_ok, r.title, r.revision, r.terms, r.kanji, r.termMeta))
                    if (r.media > 0) report.append(getString(R.string.dict_import_ok_media, r.media))
                    report.append('\n')
                }
                catch (e: Throwable)
                {
                    report.append(getString(R.string.dict_import_failed, e.message ?: e.javaClass.simpleName)).append('\n')
                }
                try
                {
                    contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                catch (ignored: Exception)
                {
                    // there was no persistable grant to release
                }
            }
        }
        finally
        {
            current = null
            if (wake.isHeld) wake.release()
            val text = report.toString().trim()
            main.post {
                lastReport = text
                for (l in listeners) l.onFinished(text)
                KakuToast.show(this, text, true)
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun notifyProgress(p: DictImport.Progress)
    {
        val text = when (p.phase)
        {
            "copy" -> getString(R.string.dict_import_copying)
            "index" -> getString(R.string.dict_import_indexing, p.title)
            "media" -> getString(R.string.dict_import_media, p.title, p.bank, p.banks)
            else -> getString(R.string.dict_import_rows, p.title, p.rows, p.bank, p.banks)
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text, p.bank, p.banks))
    }

    private fun notification(text: String, done: Int, total: Int): android.app.Notification
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            val m = getSystemService(NotificationManager::class.java)
            if (m != null && m.getNotificationChannel(CHANNEL) == null)
            {
                m.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.dict_import_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }
        return NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.kaku_notification_icon)
                .setContentTitle(getString(R.string.dict_import_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(total, done, total == 0)
                .build()
    }

    /** The UI page's view of a running import. */
    interface Listener
    {
        fun onProgress(p: DictImport.Progress)
        fun onFinished(report: String)
    }

    companion object
    {
        private const val CHANNEL = "shiroikuma_dict_import"
        private const val NOTIFICATION_ID = 9721
        private const val EXTRA_URIS = "uris"

        private val executor = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())

        val listeners = CopyOnWriteArrayList<Listener>()

        @Volatile
        var current: DictImport.Progress? = null
            private set

        @Volatile
        var lastReport: String? = null
            private set

        fun start(context: Context, uris: List<Uri>)
        {
            // A URI read grant travels with an intent's ClipData (not with its extras); the page also
            // took a persistable grant, released here once the file has been read.
            val i = Intent(context, DictImportService::class.java)
                    .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.isNotEmpty())
            {
                val clip = android.content.ClipData.newRawUri("dictionary", uris[0])
                for (u in uris.drop(1)) clip.addItem(android.content.ClipData.Item(u))
                i.clipData = clip
            }
            ContextCompat.startForegroundService(context, i)
        }
    }
}
