package shiroikuma.kaku.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import ca.fuwafuwa.kaku.R;
import shiroikuma.kaku.KakuFork;
import shiroikuma.kaku.backup.ShiroikumaExport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where every automation job runs: a data-door export or import started through
 * {@link AutomationProvider} (contract v2 §2a), and a §1 {@code EXPORT_STATE} handed over by
 * {@link StateExportReceiver} — this app's archive carries the OCR data and the dictionary, far
 * too much for a receiver's broadcast window. Ported from shiroikuma-doksho's (raikidoban's /
 * ArcaneChat's) {@code AutomationDataService}; a partial wakelock is held around every job, as EMUI
 * otherwise dozes the CPU under a long export with the screen off.
 *
 * <h3>Why a foreground service and not the provider call</h3>
 *
 * <p>The call returns in milliseconds; this writes into <b>a descriptor the caller supplied</b>,
 * which may be a pipe, so it blocks for as long as the caller is slow to drain it. A binder call
 * holding 応用管理's list for that long, or a background process being frozen mid-stream, is not an
 * option — hence a {@code dataSync} foreground service, and §3 progress with a heartbeat.
 *
 * <h3>The recipe, in the order the contract fixes it</h3>
 *
 * <ol>
 * <li><b>Read the extras.</b> No early returns here.</li>
 * <li><b>Go foreground, guarded.</b> Once {@code startForegroundService()} has been invoked the
 * platform requires {@code startForeground()} whatever this method then decides, and enforces it
 * by killing the process — so the promotion is the FIRST act, before any bail-out. It can also be
 * refused ({@code ForegroundServiceStartNotAllowedException} on a cold start); by then the provider
 * has rightly answered {@code OK:<job_id>}, so the refusal is answered with the terminal broadcast
 * carrying that id, the descriptor is closed and the job dropped.</li>
 * <li><b>Drain {@link #HANDOVER}</b> inside the same {@code try}/{@code finally}, so a throw
 * anywhere in between still closes the caller's file.</li>
 * <li><b>Then the early returns</b> — no job id, an entry already drained — which stop
 * <b>silently</b>: that id's request has already had its one terminal reply.</li>
 * </ol>
 *
 * <p>The descriptor travels through {@link #HANDOVER} rather than the Intent: a
 * {@code ParcelFileDescriptor} in an Intent extra is duplicated by the system on delivery and the
 * copy's lifetime stops being ours to reason about. One open descriptor, one owner, closed in a
 * {@code finally} on every path — a leaked one holds the caller's file open, and a caller cannot
 * checksum or encrypt a file that is still open.
 */
public class AutomationDataService extends Service {

    private static final String TAG = "ShiroikumaAutomation";

    private static final String CHANNEL = "shiroikuma_automation";
    private static final int NOTIFICATION_ID = 9714;
    private static final String EXTRA_JOB = "job";
    private static final String EXTRA_IMPORTING = "importing";
    /** A §1 state export (path / SAF directory, reply_id echoed) rather than a data-door job. */
    private static final String EXTRA_STATE = "state";
    /** Longest any one job may hold the CPU awake; far beyond a 60 MB archive. */
    private static final long WAKELOCK_MS = 30 * 60 * 1000L;

    private static final ConcurrentHashMap<String, ParcelFileDescriptor> HANDOVER = new ConcurrentHashMap<>();

    /** Called from the provider's binder thread; throws when the service cannot be started at all. */
    public static void start(@NonNull Context context, @NonNull String jobId, @NonNull ParcelFileDescriptor fd,
                             boolean importing, @Nullable Bundle extras) {
        HANDOVER.put(jobId, fd);
        Intent intent = new Intent(context, AutomationDataService.class);
        intent.putExtra(EXTRA_JOB, jobId);
        intent.putExtra(EXTRA_IMPORTING, importing);
        if (extras != null) {
            intent.putExtra(AutomationProvider.KEY_ITEMS, extras.getString(AutomationProvider.KEY_ITEMS));
            intent.putExtra(AutomationProvider.KEY_REPLY_ACTION, extras.getString(AutomationProvider.KEY_REPLY_ACTION));
            intent.putExtra(AutomationProvider.KEY_REPLY_PACKAGE, extras.getString(AutomationProvider.KEY_REPLY_PACKAGE));
            intent.putExtra(AutomationProvider.KEY_PROGRESS_ACTION, extras.getString(AutomationProvider.KEY_PROGRESS_ACTION));
        }
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable t) {
            // Never strand the descriptor if the service could not be started at all.
            HANDOVER.remove(jobId);
            throw t;
        }
    }

    /**
     * Start site 1's target: called from {@link StateExportReceiver#onReceive}; throws when the
     * service cannot be started, which the receiver answers.
     */
    public static void startStateExport(@NonNull Context context, @NonNull String path, @NonNull String items,
                                        @NonNull String progressAction, @NonNull String replyAction,
                                        @NonNull String replyPackage, @NonNull String replyId) {
        Intent intent = new Intent(context, AutomationDataService.class);
        intent.putExtra(EXTRA_STATE, true);
        intent.putExtra(StateExportReceiver.EXTRA_PATH, path);
        intent.putExtra(AutomationProvider.KEY_ITEMS, items);
        intent.putExtra(AutomationProvider.KEY_PROGRESS_ACTION, progressAction);
        intent.putExtra(AutomationProvider.KEY_REPLY_ACTION, replyAction);
        intent.putExtra(AutomationProvider.KEY_REPLY_PACKAGE, replyPackage);
        intent.putExtra(StateExportReceiver.EXTRA_REPLY_ID, replyId);
        ContextCompat.startForegroundService(context, intent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, final int startId) {
        // 1. The extras — microseconds, no early returns.
        final String jobId = intent == null ? null : intent.getStringExtra(EXTRA_JOB);
        final boolean importing = intent != null && intent.getBooleanExtra(EXTRA_IMPORTING, false);
        final String items = intent == null ? null : intent.getStringExtra(AutomationProvider.KEY_ITEMS);
        final String replyAction = intent == null ? null : intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION);
        final String replyPackage = intent == null ? null : intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE);
        final String progressAction = intent == null ? null : intent.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION);
        final boolean state = intent != null && intent.getBooleanExtra(EXTRA_STATE, false);
        final String path = intent == null ? null : intent.getStringExtra(StateExportReceiver.EXTRA_PATH);
        final String replyId = intent == null ? null : intent.getStringExtra(StateExportReceiver.EXTRA_REPLY_ID);

        final Context app = getApplicationContext();
        if (state) return onStateExport(app, startId, path, items, progressAction, replyAction, replyPackage, replyId);
        final AtomicBoolean replied = new AtomicBoolean(false);
        final Replier reply = result -> sendReply(app, replied, replyAction, replyPackage, jobId, result);

        ParcelFileDescriptor fd = null;
        boolean handedOff = false;
        try {
            // 2. Foreground, guarded — the one site where an OK has genuinely already been sent.
            try {
                enterForeground(importing);
            } catch (Throwable t) {
                Log.w(TAG, "foreground start refused", t);
                if (jobId != null) fd = HANDOVER.remove(jobId);
                reply.send(AutomationForeground.refusal(app, t));
                return stop(startId);
            }

            // 3. Drain the handover inside the same try/finally.
            if (jobId == null) return stop(startId);
            fd = HANDOVER.remove(jobId);
            if (fd == null) {
                // 4. Stale or already-claimed id: that request has had its one reply. Silence.
                AutomationJobs.finish(jobId);
                return stop(startId);
            }

            final ParcelFileDescriptor owned = fd;
            new Thread(() -> {
                PowerManager.WakeLock wake = acquireWakeLock(app);
                try {
                    if (importing) {
                        runImport(app, owned, jobId, progressAction, replyPackage, reply);
                    } else {
                        runExport(app, owned, jobId, items, progressAction, replyPackage, reply);
                    }
                } catch (ShiroikumaExport.CancelledException e) {
                    reply.send("ERROR:cancelled");
                } catch (Throwable t) {
                    Log.w(TAG, "automation data job failed", t);
                    reply.send("ERROR:" + AutomationForeground.oneLine(t));
                } finally {
                    releaseQuietly(wake);
                    closeQuietly(owned);
                    AutomationJobs.finish(jobId);
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                    stopSelf(startId);
                }
            }, "shiroikuma-automation-data").start();
            handedOff = true;
        } finally {
            if (!handedOff && fd != null) {
                // The worker never took ownership, so the descriptor is still ours to close.
                closeQuietly(fd);
                reply.send("ERROR:could not start the job");
                AutomationJobs.finish(jobId);
            }
        }
        return START_NOT_STICKY;
    }

    // --- §1 state export -------------------------------------------------------------------------

    /**
     * A §1 {@code EXPORT_STATE}, in the contract's order: the extras are already read; go
     * foreground, guarded (start site 2 — a refusal is answered through the reply trio read above,
     * never lost); only then the work. The "already running" guard lives inside
     * {@link ShiroikumaExport#export} and is claimed only after the promotion succeeded.
     */
    private int onStateExport(Context app, int startId, @Nullable String path, @Nullable String items,
                              @Nullable String progressAction, @Nullable String replyAction,
                              @Nullable String replyPackage, @Nullable String replyId) {
        final AtomicBoolean replied = new AtomicBoolean(false);
        try {
            enterForeground(false);
        } catch (Throwable t) {
            Log.w(TAG, "foreground start refused", t);
            StateExportReceiver.reply(app, replied, replyAction, replyPackage, replyId, AutomationForeground.refusal(app, t));
            return stop(startId);
        }

        final StateExportReceiver.Run run = new StateExportReceiver.Run(replyId == null ? "" : replyId);
        StateExportReceiver.RUNNING.add(run);
        new Thread(() -> {
            PowerManager.WakeLock wake = acquireWakeLock(app);
            String result;
            AutomationProgress progress = null;
            try {
                Set<ShiroikumaExport.Cat> cats = StateExportReceiver.resolveItems(items);
                progress = new AutomationProgress(app, progressAction, replyPackage, replyId,
                        new String[]{StateExportReceiver.EXTRA_REPLY_ID}, cats, null);
                progress.start();
                result = runStateExport(app, path, cats, progress, run);
            } catch (ShiroikumaExport.CancelledException e) {
                result = "ERROR:cancelled";
            } catch (IllegalArgumentException e) {
                result = "ERROR:unknown category in items: " + String.valueOf(items).trim();
            } catch (Throwable t) {
                Log.w(TAG, "headless export failed", t);
                result = "ERROR:" + AutomationForeground.oneLine(t);
            } finally {
                if (progress != null) progress.stop();
                StateExportReceiver.RUNNING.remove(run);
                releaseQuietly(wake);
            }
            StateExportReceiver.reply(app, replied, replyAction, replyPackage, replyId, result);
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            } catch (Throwable ignored) {
                // pass
            }
            stopSelf(startId);
        }, "shiroikuma-state-export").start();
        return START_NOT_STICKY;
    }

    /**
     * Directory precedence for this app, which does not declare {@code MANAGE_EXTERNAL_STORAGE}: the
     * configured SAF directory (a {@code path} extra is then ignored, per §1) → with none and a
     * {@code path} given, {@code ERROR:no-storage-access} → with neither, {@code ERROR:no-directory}.
     * The archive is written as {@code .part}, renamed when complete, and deleted on cancel or failure.
     */
    private static String runStateExport(Context app, @Nullable String path, Set<ShiroikumaExport.Cat> cats,
                                         AutomationProgress progress, StateExportReceiver.Run run) throws Exception {
        DocumentFile dir = ShiroikumaExport.exportDir(app);
        if (dir == null) {
            return (path != null && !path.trim().isEmpty()) ? "ERROR:no-storage-access" : "ERROR:no-directory";
        }
        ShiroikumaExport.Written written = ShiroikumaExport.exportToDirectory(app, dir, cats, progress, run.cancelled::get);
        return "OK:" + written.path + "|" + written.bytes + "|" + ShiroikumaExport.humanSize(written.bytes)
                + "|" + cats.size() + " categories";
    }

    @Nullable
    private static PowerManager.WakeLock acquireWakeLock(Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiroikuma.kaku:automation");
            wake.setReferenceCounted(false);
            wake.acquire(WAKELOCK_MS);
            return wake;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void releaseQuietly(@Nullable PowerManager.WakeLock wake) {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Throwable ignored) {
            // pass
        }
    }

    // --- export ---------------------------------------------------------------------------------

    /**
     * Writes the category ZIP straight into the caller's descriptor. The bytes are counted as they
     * go rather than stat'ed afterwards: the caller owns the file and we may not be able to see it.
     */
    private void runExport(Context app, ParcelFileDescriptor fd, String jobId, @Nullable String items,
                           @Nullable String progressAction, @Nullable String replyPackage, Replier reply)
            throws IOException {
        final Set<ShiroikumaExport.Cat> cats;
        try {
            cats = StateExportReceiver.resolveItems(items);
        } catch (IllegalArgumentException e) {
            reply.send("ERROR:unknown category in items: " + String.valueOf(items).trim());
            return;
        }

        final long[] written = {0};
        AutomationProgress progress = new AutomationProgress(app, progressAction, replyPackage, jobId,
                new String[]{AutomationProvider.KEY_JOB_ID, AutomationProvider.KEY_REPLY_ID}, cats, () -> written[0]);
        progress.start();
        OutputStream raw = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
        try {
            OutputStream counting = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    raw.write(b);
                    written[0]++;
                }

                @Override
                public void write(@NonNull byte[] b, int off, int len) throws IOException {
                    raw.write(b, off, len);
                    written[0] += len;
                }

                @Override
                public void flush() throws IOException {
                    raw.flush();
                }
            };
            ShiroikumaExport.export(app, cats, counting, progress, () -> AutomationJobs.isCancelled(jobId));
            counting.flush();
        } finally {
            progress.stop();
            raw.close();
        }

        if (AutomationJobs.isCancelled(jobId)) {
            reply.send("ERROR:cancelled");
        } else {
            reply.send("OK:" + written[0] + "|" + ShiroikumaExport.humanSize(written[0]) + "|" + cats.size() + " categories");
        }
    }

    // --- import — the half that exists ONLY behind the provider ---------------------------------

    /**
     * Spool the archive to a cache file, validate it there, then apply it. Nothing is written until
     * the whole archive has arrived and been checked; the bound is disk, not RAM.
     */
    private void runImport(Context app, ParcelFileDescriptor fd, String jobId, @Nullable String progressAction,
                           @Nullable String replyPackage, Replier reply) throws IOException {
        File spool = new File(getCacheDir(), "automation-import-" + jobId + ".zip");
        AutomationProgress progress = new AutomationProgress(app, progressAction, replyPackage, jobId,
                new String[]{AutomationProvider.KEY_JOB_ID, AutomationProvider.KEY_REPLY_ID},
                ShiroikumaExport.Cat.defaults(), null);
        progress.start();
        try {
            progress.note(app.getString(R.string.kaku_auto_notif_spooling));
            long total = 0;
            InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
            try {
                OutputStream out = new FileOutputStream(spool);
                try {
                    byte[] chunk = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        out.write(chunk, 0, read);
                        total += read;
                    }
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (total == 0) {
                reply.send("ERROR:empty archive");
                return;
            }

            // Every category the archive actually carries, not every category we know about.
            List<String> present = ShiroikumaExport.categoriesIn(spool);
            if (present.isEmpty()) {
                reply.send("ERROR:archive carries no categories");
                return;
            }
            Set<ShiroikumaExport.Cat> cats = new LinkedHashSet<>();
            for (String id : present) {
                ShiroikumaExport.Cat cat = ShiroikumaExport.Cat.byId(id);
                if (cat != null) cats.add(cat);
            }
            progress.setCategories(cats);
            progress.onProgress(1, cats.size(), app.getString(cats.iterator().next().labelRes));
            // ShiroikumaExport merges with commit(): 応用管理 force-stops us the instant we answer OK
            // (a SIGKILL), and an apply() still in flight would be lost with the restore.
            ShiroikumaExport.importZip(app, spool, cats);
            reply.send("OK:" + cats.size() + " categories restored");
        } finally {
            progress.stop();
            // Never leave it behind: it is a complete copy of the backup sitting in cache.
            if (spool.exists() && !spool.delete()) spool.deleteOnExit();
        }
    }

    // --- reply + foreground ---------------------------------------------------------------------

    /** The one terminal answer per job — a fresh broadcast carrying the job id under both names. */
    private static void sendReply(Context app, AtomicBoolean replied, @Nullable String replyAction,
                                  @Nullable String replyPackage, @Nullable String jobId, String result) {
        if (!replied.compareAndSet(false, true)) return;
        if (isEmpty(replyAction) || isEmpty(replyPackage)) {
            Log.i(TAG, "job " + jobId + " finished with no reply channel: " + result);
            return;
        }
        try {
            Intent out = new Intent(replyAction);
            out.setPackage(replyPackage);
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            out.putExtra(AutomationProvider.KEY_JOB_ID, jobId);
            out.putExtra(AutomationProvider.KEY_REPLY_ID, jobId);
            out.putExtra(AutomationProvider.KEY_RESULT, result);
            app.sendBroadcast(out);
            Log.i(TAG, "replied to " + replyPackage + " [" + jobId + "]: " + result);
        } catch (Throwable t) {
            Log.w(TAG, "could not deliver the reply", t);
        }
    }

    /**
     * {@code dataSync}, typed only where the type exists (API 29+) — the plain overload below that.
     * Throws through to the caller, which answers the refusal; never swallowed here.
     */
    private void enterForeground(boolean importing) {
        Notification notification = buildNotification(importing);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(boolean importing) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                        getString(R.string.kaku_auto_notif_channel), NotificationManager.IMPORTANCE_LOW));
            }
        }
        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle(KakuFork.NAME)
                .setContentText(getString(importing ? R.string.kaku_auto_notif_import : R.string.kaku_auto_notif_export))
                .setSmallIcon(R.drawable.kaku_notification_icon)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    /** Always paired with the foreground call above: a bail-out must not leave a live notification. */
    private int stop(int startId) {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
            // we may never have been foreground at all
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private static void closeQuietly(@Nullable ParcelFileDescriptor fd) {
        try {
            if (fd != null) fd.close();
        } catch (Exception ignored) {
            // an already-closed descriptor is the normal case here
        }
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    private interface Replier {
        void send(String result);
    }
}
