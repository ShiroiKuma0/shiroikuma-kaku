package shiroikuma.kaku.automation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import shiroikuma.kaku.backup.ShiroikumaExport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The sister-app <b>state-export automation contract</b> (保存復元, v2 §1) — the wire shape every
 * 白い熊 app exposes so one 自由作業盤 task can back them all up headlessly. Ported from
 * shiroikuma-doksho's {@code StateExportReceiver}, with one deliberate difference below.
 *
 * <ul>
 * <li>{@code shiroikuma.kaku.action.EXPORT_STATE}: run the category ZIP ({@link ShiroikumaExport})
 * with no UI. Extras (all String): {@code token} (optional — checked only while 「Use authorization
 * token?」 is on, ignored otherwise), {@code path} (optional absolute directory), {@code items}
 * (optional comma list of category ids; absent = the default set), {@code progress_action}
 * (optional), plus the reply trio {@code reply_action} / {@code reply_package} / {@code reply_id}.</li>
 * <li>{@code shiroikuma.kaku.action.LIST_CATEGORIES}: gated the same way, instant. One
 * {@code id<TAB>label<TAB>parent<TAB>on|off} line per category.</li>
 * <li>{@code shiroikuma.kaku.action.CANCEL_EXPORT}: stop a running export. Fire-and-forget — it never
 * replies, and is a silent no-op when nothing is running. The export unwinds at the next write
 * boundary, deletes the half-written archive, and answers its ORIGINAL request with
 * {@code ERROR:cancelled}.</li>
 * </ul>
 *
 * <h3>Why the export runs in a foreground service, not in {@code goAsync()}</h3>
 *
 * <p>The contract allows {@code goAsync()} only for an export that cannot exceed a few seconds. This
 * app's archive carries the OCR data and the dictionary database — tens of megabytes, deflated on
 * the phone — so the receiver does nothing but gate, validate {@code items} and hand the request to
 * {@link AutomationDataService} (start site 1, guarded: a refused background start is answered, never
 * thrown out of {@code onReceive}). The service does the export, the progress and the one terminal
 * reply.
 *
 * <h3>Storage</h3>
 *
 * <p>This app does <b>not</b> declare {@code MANAGE_EXTERNAL_STORAGE} and should not: it never
 * needs arbitrary storage. So per §1 a {@code path} extra is ignored when the configured SAF export
 * directory exists (the archive goes there), and answered {@code ERROR:no-storage-access} when there
 * is none.
 *
 * <p>Reply: a FRESH broadcast to {@code reply_package} with {@code reply_id} echoed verbatim and
 * {@code result} = {@code OK:<path>|<bytes>|<human size>|<n> categories}, {@code OK:} + the
 * category lines, or {@code ERROR:<reason>}. Exactly one terminal reply per request.
 *
 * <p>Exported with NO {@code android:permission}: in v2 this receiver is deliberately the
 * unauthenticated half of the surface — it only ever writes where it was told to and reports what
 * it did. Everything that moves data through a caller-supplied descriptor lives behind
 * {@link AutomationProvider}, which knows who is calling. There is no import here.
 */
public class StateExportReceiver extends BroadcastReceiver {

    private static final String TAG = "ShiroikumaStateExport";

    // <applicationId>.action.* — the fork's id, fixed (shiroikuma/fork.gradle).
    private static final String PKG = "shiroikuma.kaku";
    public static final String ACTION_EXPORT_STATE = PKG + ".action.EXPORT_STATE";
    public static final String ACTION_LIST_CATEGORIES = PKG + ".action.LIST_CATEGORIES";
    public static final String ACTION_CANCEL_EXPORT = PKG + ".action.CANCEL_EXPORT";

    // Contract extras — bare names, shared verbatim by every sister app.
    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_PATH = "path";
    static final String EXTRA_ITEMS = "items";
    static final String EXTRA_PROGRESS_ACTION = "progress_action";
    static final String EXTRA_REPLY_ACTION = "reply_action";
    static final String EXTRA_REPLY_PACKAGE = "reply_package";
    static final String EXTRA_REPLY_ID = "reply_id";
    static final String EXTRA_RESULT = "result";

    /**
     * The §1 exports currently writing, so a CANCEL_EXPORT arriving on a fresh receiver instance can
     * reach them. Static because a receiver is rebuilt per delivery; the service removes its run in
     * its worker's finally, so "nothing is running" is the normal state. Never persisted.
     */
    static final List<Run> RUNNING = new CopyOnWriteArrayList<>();

    /** One in-flight §1 export: the request it answers, and the flag that stops it. */
    static final class Run {
        final String replyId;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        Run(String replyId) {
            this.replyId = replyId;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final Context app = context.getApplicationContext();
        final String action = intent.getAction();
        final String token = intent.getStringExtra(EXTRA_TOKEN);
        final String replyAction = trimmed(intent.getStringExtra(EXTRA_REPLY_ACTION));
        final String replyPackage = trimmed(intent.getStringExtra(EXTRA_REPLY_PACKAGE));
        final String replyId = trimmed(intent.getStringExtra(EXTRA_REPLY_ID));
        final String progressAction = trimmed(intent.getStringExtra(EXTRA_PROGRESS_ACTION));
        final String pathOverride = trimmed(intent.getStringExtra(EXTRA_PATH));
        final String items = trimmed(intent.getStringExtra(EXTRA_ITEMS));

        // Cancel is handled ahead of the replying gate: it never answers anything, and a rejected
        // token is silence too. Safe to send at any time — when nothing matches, nothing happens.
        if (ACTION_CANCEL_EXPORT.equals(action)) {
            if (AutomationAuth.refuse(app, token) == null) {
                for (Run run : RUNNING) {
                    if (replyId.isEmpty() || replyId.equals(run.replyId)) run.cancelled.set(true);
                }
            }
            return;
        }
        if (!ACTION_EXPORT_STATE.equals(action) && !ACTION_LIST_CATEGORIES.equals(action)) return;

        if (replyAction.isEmpty() || replyPackage.isEmpty()) {
            // Nobody to answer: do NOT degrade to an implicit broadcast (setPackage(null) reaches
            // no manifest receiver since API 26 anyway).
            Log.w(TAG, "ignoring " + action + " — no reply channel (reply_action / reply_package)");
            return;
        }

        final AtomicBoolean replied = new AtomicBoolean(false);

        // Gate first, in ONE place (contract §2).
        String refusal = AutomationAuth.refuse(app, token);
        if (refusal != null) {
            reply(app, replied, replyAction, replyPackage, replyId, refusal);
            return;
        }

        if (ACTION_LIST_CATEGORIES.equals(action)) {
            reply(app, replied, replyAction, replyPackage, replyId, listCategories(app));
            return;
        }

        try {
            resolveItems(items);
        } catch (IllegalArgumentException e) {
            reply(app, replied, replyAction, replyPackage, replyId, "ERROR:unknown category in items: " + items);
            return;
        }

        // Start site 1: a broadcast is a background start on API 31+ and can be refused. Answer it.
        try {
            AutomationDataService.startStateExport(app, pathOverride, items, progressAction,
                    replyAction, replyPackage, replyId);
        } catch (Throwable t) {
            Log.w(TAG, "could not start the export service", t);
            reply(app, replied, replyAction, replyPackage, replyId, AutomationForeground.refusal(app, t));
        }
    }

    /** The one terminal reply of a §1 request: a fresh broadcast, never a binder. */
    static void reply(@NonNull Context app, @NonNull AtomicBoolean replied, @Nullable String replyAction,
                      @Nullable String replyPackage, @Nullable String replyId, @NonNull String result) {
        if (!replied.compareAndSet(false, true)) return;
        if (replyAction == null || replyAction.isEmpty() || replyPackage == null || replyPackage.isEmpty()) {
            Log.i(TAG, "finished with no reply channel: " + result);
            return;
        }
        try {
            Intent out = new Intent(replyAction);
            out.setPackage(replyPackage);
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            out.putExtra(EXTRA_REPLY_ID, replyId);
            out.putExtra(EXTRA_RESULT, result);
            app.sendBroadcast(out);
            Log.i(TAG, "replied to " + replyPackage + " [" + replyId + "]: " + result);
        } catch (Throwable t) {
            Log.w(TAG, "could not deliver the reply", t);
        }
    }

    // --- LIST_CATEGORIES ------------------------------------------------------------------------

    /** {@code OK:} + one {@code id<TAB>label<TAB>parent<TAB>on|off} line per category. */
    static String listCategories(@NonNull Context app) {
        StringBuilder sb = new StringBuilder("OK:");
        boolean first = true;
        for (ShiroikumaExport.Cat cat : ShiroikumaExport.Cat.values()) {
            if (!first) sb.append('\n');
            first = false;
            // The parent field stays present but empty for a top-level category, because the
            // "starts ticked" flag after it is positional.
            sb.append(cat.id).append('\t').append(app.getString(cat.labelRes))
                    .append('\t').append(cat.parentId == null ? "" : cat.parentId)
                    .append('\t').append(cat.defaultSelected ? "on" : "off");
        }
        return sb.toString();
    }

    // --- EXPORT_STATE ---------------------------------------------------------------------------

    /**
     * The categories an {@code items} extra asks for; absent/empty = the default set. Shared with
     * {@link AutomationDataService}, which resolves the same grammar for both doors.
     *
     * @throws IllegalArgumentException naming the first unknown id
     */
    @NonNull
    static Set<ShiroikumaExport.Cat> resolveItems(@Nullable String items) {
        if (items == null || items.trim().isEmpty()) return ShiroikumaExport.Cat.defaults();
        Set<ShiroikumaExport.Cat> resolved = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : items.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) continue;
            ShiroikumaExport.Cat cat = ShiroikumaExport.Cat.byId(id);
            if (cat == null) unknown.add(id);
            else resolved.add(cat);
        }
        if (!unknown.isEmpty()) throw new IllegalArgumentException(unknown.get(0));
        return resolved.isEmpty() ? ShiroikumaExport.Cat.defaults() : resolved;
    }

    private static String trimmed(@Nullable String s) {
        return s == null ? "" : s.trim();
    }
}
