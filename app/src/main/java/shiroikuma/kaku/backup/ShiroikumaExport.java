package shiroikuma.kaku.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.documentfile.provider.DocumentFile;

import ca.fuwafuwa.kaku.Constants;
import ca.fuwafuwa.kaku.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import shiroikuma.kaku.KakuFonts;
import shiroikuma.kaku.KakuUi;

/**
 * The Export / Import engine of 白い熊 画 — the category ZIP the UI page writes, in the family's
 * shared shape (ported from 白い熊 読書's {@code ShiroikumaExport}).
 *
 * <p>Categories:
 * <ul>
 *   <li>{@link Cat#UI} — the 白い熊 画 UI page ({@link KakuUi#PREFS}) and the imported fonts;</li>
 *   <li>{@link Cat#SETTINGS} — the app's own settings (instant mode, image filter, text direction),
 *   minus the device-local state keys;</li>
 *   <li>{@link Cat#OCR} — the OCR data in {@code files/tessdata} (and, later, the imported OCR
 *   models), so a new phone needs nothing imported by hand;</li>
 *   <li>{@link Cat#DICTIONARY} — the dictionary database(s) in {@code files}.</li>
 * </ul>
 * The export directory lives in its own device-local prefs file, never exported.
 *
 * <pre>
 * manifest.json   {"format":"shiroikuma-kaku","version":1,"app":…,"appVersion":…,"createdTs":…,"categories":[…]}
 * ui.json         {"prefs":{"&lt;key&gt;":{"t":…,"v":…}}}      fonts/&lt;file&gt;
 * settings.json   {"prefs":{"&lt;key&gt;":{"t":…,"v":…}}}
 * ocr.json        {"files":["jpn.traineddata",…]}          ocr/&lt;file&gt;
 * dictionary.json {"files":["DB_KakuDict-….db",…]}         dictionary/&lt;file&gt;
 * </pre>
 *
 * <p>Import merges prefs per key ({@code commit()}) and replaces the carried data files atomically;
 * the data takes effect after the restart the import dialog offers. Archives are written as
 * {@code <name>.part} and renamed only once complete.
 */
public final class ShiroikumaExport {

    public static final String SLUG = "shiroikuma-kaku";
    public static final String FORMAT = SLUG;
    public static final int VERSION = 1;

    /** Family-wide backup-name convention: {@code shiroikuma-kaku_<yyyy-MM-dd_HH-mm-ss>.zip}. */
    public static final String EXPORT_PREFIX = SLUG + "_";
    public static final String PART_SUFFIX = ".part";

    public static final String MANIFEST_ENTRY = "manifest.json";

    /** Device-local prefs holding the export directory (a SAF tree Uri); never exported. */
    public static final String PREFS_EXIMPORT = "kaku_eximport";
    private static final String KEY_DIR_URI = "dir_uri";

    /** The app's own settings file and the keys in it that are this device's state, not a choice. */
    private static final String PREFS_APP = Constants.KAKU_PREF_FILE;
    private static final Set<String> APP_DEVICE_KEYS = new HashSet<>(Arrays.asList(
            Constants.KAKU_PREF_SHOW_HIDE, Constants.KAKU_PREF_FIRST_LAUNCH));

    private static final String FONTS_PREFIX = "fonts/";
    private static final String OCR_PREFIX = "ocr/";
    private static final String DICT_PREFIX = "dictionary/";

    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

    private static final AtomicBoolean EXPORT_RUNNING = new AtomicBoolean(false);

    private ShiroikumaExport() {
    }

    // ---------------------------------------------------------------------------------------------
    // categories
    // ---------------------------------------------------------------------------------------------

    public enum Cat {
        UI("ui", R.string.kaku_eim_cat_ui, null, true),
        SETTINGS("settings", R.string.kaku_eim_cat_settings, null, true),
        OCR("ocr", R.string.kaku_eim_cat_ocr, null, true),
        DICTIONARY("dictionary", R.string.kaku_eim_cat_dictionary, null, true);

        public final String id;
        @StringRes
        public final int labelRes;
        @Nullable
        public final String parentId;
        public final boolean defaultSelected;

        Cat(String id, @StringRes int labelRes, @Nullable String parentId, boolean defaultSelected) {
            this.id = id;
            this.labelRes = labelRes;
            this.parentId = parentId;
            this.defaultSelected = defaultSelected;
        }

        @Nullable
        public static Cat byId(@Nullable String id) {
            if (id == null) return null;
            for (Cat c : values()) {
                if (c.id.equals(id)) return c;
            }
            return null;
        }

        public static Set<Cat> all() {
            Set<Cat> out = new LinkedHashSet<>();
            Collections.addAll(out, values());
            return out;
        }

        public static Set<Cat> defaults() {
            Set<Cat> out = new LinkedHashSet<>();
            for (Cat c : values()) {
                if (c.defaultSelected) out.add(c);
            }
            return out;
        }
    }

    public interface Progress {
        void onProgress(int done, int total, String categoryLabel);
    }

    public interface Cancel {
        boolean isCancelled();
    }

    public static class CancelledException extends IOException {
        public CancelledException() {
            super("cancelled");
        }
    }

    public static final class Written {
        public final String path;
        public final long bytes;

        Written(String path, long bytes) {
            this.path = path;
            this.bytes = bytes;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // where the data lives
    // ---------------------------------------------------------------------------------------------

    /** The OCR data directory ({@code files/tessdata}). */
    @NonNull
    public static File ocrDir(@NonNull Context context) {
        return new File(context.getApplicationContext().getFilesDir(), Constants.TESS_FOLDER_NAME);
    }

    /** The OCR data files that travel: every traineddata / model file in {@link #ocrDir}. */
    @NonNull
    public static List<File> ocrFiles(@NonNull Context context) {
        List<File> out = new ArrayList<>();
        File[] files = ocrDir(context).listFiles();
        if (files != null) for (File f : files) if (f.isFile() && isOcrName(f.getName())) out.add(f);
        return out;
    }

    private static boolean isOcrName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".traineddata") || n.endsWith(".onnx") || n.endsWith(".txt") || n.endsWith(".json");
    }

    /** The dictionary directory ({@code files}: the dictionary database lives at its top). */
    @NonNull
    public static File dictDir(@NonNull Context context) {
        return context.getApplicationContext().getFilesDir();
    }

    /** The dictionary files that travel: the SQLite databases in {@link #dictDir}. */
    @NonNull
    public static List<File> dictFiles(@NonNull Context context) {
        List<File> out = new ArrayList<>();
        File[] files = dictDir(context).listFiles();
        if (files != null) for (File f : files) if (f.isFile() && isDictName(f.getName())) out.add(f);
        return out;
    }

    private static boolean isDictName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".db");
    }

    // ---------------------------------------------------------------------------------------------
    // file name + export directory
    // ---------------------------------------------------------------------------------------------

    public static String exportFileName() {
        return EXPORT_PREFIX + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date()) + ".zip";
    }

    public static boolean isExportFileName(@Nullable String name) {
        return name != null && name.startsWith(EXPORT_PREFIX) && name.endsWith(".zip");
    }

    private static SharedPreferences eximportPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_EXIMPORT, Context.MODE_PRIVATE);
    }

    @Nullable
    public static Uri exportDirUri(Context context) {
        String raw = eximportPrefs(context).getString(KEY_DIR_URI, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Uri.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setExportDirUri(Context context, @Nullable Uri uri) {
        if (uri != null) {
            try {
                context.getContentResolver().takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                // Some providers refuse a persistable grant; the session grant still works for now.
            }
        }
        eximportPrefs(context).edit().putString(KEY_DIR_URI, uri == null ? null : uri.toString()).commit();
    }

    @Nullable
    public static DocumentFile exportDir(Context context) {
        Uri uri = exportDirUri(context);
        if (uri == null) return null;
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(context, uri);
            return (dir != null && dir.isDirectory()) ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static String absolutePathOf(@Nullable Uri treeUri, @Nullable String fileName) {
        if (treeUri == null || !EXTERNAL_STORAGE_AUTHORITY.equals(treeUri.getAuthority())) return null;
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return null;
        }
        if (docId == null || !docId.startsWith("primary:")) return null;
        String rel = docId.substring("primary:".length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        String base = Environment.getExternalStorageDirectory().getAbsolutePath();
        String path = rel.isEmpty() ? base : base + "/" + rel;
        return fileName == null ? path : path + "/" + fileName;
    }

    @Nullable
    public static String dirLabel(Context context) {
        DocumentFile dir = exportDir(context);
        if (dir == null) return null;
        String abs = absolutePathOf(dir.getUri(), null);
        return abs != null ? abs : dir.getName();
    }

    @Nullable
    public static DocumentFile newestExport(Context context) {
        DocumentFile dir = exportDir(context);
        if (dir == null) return null;
        DocumentFile newest = null;
        try {
            for (DocumentFile f : dir.listFiles()) {
                if (f.isFile() && isExportFileName(f.getName())
                        && (newest == null || f.lastModified() > newest.lastModified())) {
                    newest = f;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return newest;
    }

    public static String humanSize(long bytes) {
        if (bytes >= (1L << 30)) return String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= (1L << 20)) return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
        if (bytes >= (1L << 10)) return String.format(Locale.ROOT, "%.1f KB", bytes / (double) (1L << 10));
        return bytes + " B";
    }

    // ---------------------------------------------------------------------------------------------
    // EXPORT
    // ---------------------------------------------------------------------------------------------

    /**
     * Write a ZIP of the selected categories to {@code out} (which the caller closes). The headless
     * core: the panel, the broadcast receiver and the data-door service are thin callers.
     */
    public static void export(@NonNull Context context, @NonNull Set<Cat> cats, @NonNull OutputStream out,
                              @Nullable Progress progress, @Nullable Cancel cancel) throws IOException {
        if (!EXPORT_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("export already running");
        }
        try {
            Context app = context.getApplicationContext();
            List<Cat> ordered = new ArrayList<>();
            for (Cat c : Cat.values()) {
                if (cats.contains(c)) ordered.add(c);
            }
            ZipOutputStream zip = new ZipOutputStream(out);
            boolean complete = false;
            try {
                writeEntry(zip, MANIFEST_ENTRY, manifest(app, ordered));
                int total = ordered.size();
                int n = 0;
                for (Cat cat : ordered) {
                    throwIfCancelled(cancel);
                    n++;
                    if (progress != null) progress.onProgress(n, total, app.getString(cat.labelRes));
                    switch (cat) {
                        case UI: {
                            JSONObject o = new JSONObject();
                            o.put("prefs", dumpPrefs(app.getSharedPreferences(KakuUi.PREFS, Context.MODE_PRIVATE), null));
                            writeEntry(zip, "ui.json", o.toString(2));
                            File[] fonts = KakuFonts.dir(app).listFiles();
                            if (fonts != null) {
                                for (File f : fonts) {
                                    throwIfCancelled(cancel);
                                    if (f.isFile() && KakuFonts.isFontName(f.getName())) {
                                        writeFileEntry(zip, FONTS_PREFIX + f.getName(), f, cancel);
                                    }
                                }
                            }
                            break;
                        }
                        case SETTINGS: {
                            JSONObject o = new JSONObject();
                            o.put("prefs", dumpPrefs(app.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE), APP_DEVICE_KEYS));
                            writeEntry(zip, "settings.json", o.toString(2));
                            break;
                        }
                        case OCR: {
                            List<File> files = ocrFiles(app);
                            writeEntry(zip, "ocr.json", new JSONObject().put("files", names(files)).toString(2));
                            for (File f : files) {
                                throwIfCancelled(cancel);
                                writeFileEntry(zip, OCR_PREFIX + f.getName(), f, cancel);
                            }
                            break;
                        }
                        case DICTIONARY: {
                            List<File> files = dictFiles(app);
                            writeEntry(zip, "dictionary.json", new JSONObject().put("files", names(files)).toString(2));
                            for (File f : files) {
                                throwIfCancelled(cancel);
                                writeFileEntry(zip, DICT_PREFIX + f.getName(), f, cancel);
                            }
                            break;
                        }
                    }
                }
                throwIfCancelled(cancel);
                zip.finish();
                zip.flush();
                complete = true;
            } catch (JSONException e) {
                throw new IOException("cannot serialise: " + e.getMessage(), e);
            } finally {
                if (complete) {
                    zip.close();
                } else {
                    try {
                        zip.close();
                    } catch (IOException ignored) {
                        // the stream may already be broken; the caller deletes the partial anyway
                    }
                }
            }
        } finally {
            EXPORT_RUNNING.set(false);
        }
    }

    @NonNull
    public static Written exportToDirectory(@NonNull Context context, @NonNull DocumentFile dir, @NonNull Set<Cat> cats,
                                            @Nullable Progress progress, @Nullable Cancel cancel) throws IOException {
        String name = exportFileName();
        DocumentFile partial = dir.createFile("application/octet-stream", name + PART_SUFFIX);
        if (partial == null) throw new IOException("cannot create " + name + PART_SUFFIX + " in the export directory");
        boolean done = false;
        try {
            OutputStream os = context.getContentResolver().openOutputStream(partial.getUri(), "w");
            if (os == null) throw new IOException("cannot open " + name + PART_SUFFIX + " for writing");
            try {
                export(context, cats, os, progress, cancel);
                os.flush();
            } finally {
                os.close();
            }
            DocumentFile finished = renameOrCopy(context, dir, partial, name);
            done = true;
            long bytes = finished.length();
            String abs = absolutePathOf(dir.getUri(), finished.getName() == null ? name : finished.getName());
            return new Written(abs != null ? abs : (dir.getName() + "/" + name), bytes);
        } finally {
            if (!done) deleteQuietly(partial);
        }
    }

    private static DocumentFile renameOrCopy(Context context, DocumentFile dir, DocumentFile partial, String name)
            throws IOException {
        boolean renamed;
        try {
            renamed = partial.renameTo(name);
        } catch (Exception e) {
            renamed = false;
        }
        if (renamed && name.equals(partial.getName())) {
            return partial;
        }
        DocumentFile target = dir.createFile("application/zip", name);
        if (target == null) throw new IOException("cannot create " + name + " in the export directory");
        boolean copied = false;
        try {
            InputStream in = context.getContentResolver().openInputStream(partial.getUri());
            OutputStream out = context.getContentResolver().openOutputStream(target.getUri(), "w");
            if (in == null || out == null) throw new IOException("cannot copy " + name + " into place");
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                in.close();
                out.close();
            }
            copied = true;
        } finally {
            if (!copied) deleteQuietly(target);
        }
        deleteQuietly(partial);
        return target;
    }

    /** The plain-{@code File} twin, for an automation {@code path} extra (All-files access granted). */
    @NonNull
    public static Written exportToFile(@NonNull Context context, @NonNull File dir, @NonNull Set<Cat> cats,
                                       @Nullable Progress progress, @Nullable Cancel cancel) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        if (!dir.isDirectory()) throw new IOException("not a directory: " + dir.getAbsolutePath());
        String name = exportFileName();
        File target = new File(dir, name);
        File partial = new File(dir, name + PART_SUFFIX);
        boolean done = false;
        try {
            OutputStream os = new FileOutputStream(partial);
            try {
                export(context, cats, os, progress, cancel);
                os.flush();
            } finally {
                os.close();
            }
            if (!partial.renameTo(target)) {
                throw new IOException("cannot rename " + partial.getName() + " to " + target.getName());
            }
            done = true;
            return new Written(target.getAbsolutePath(), target.length());
        } finally {
            if (!done) //noinspection ResultOfMethodCallIgnored
                partial.delete();
        }
    }

    private static String manifest(Context app, List<Cat> cats) throws JSONException {
        JSONArray ids = new JSONArray();
        for (Cat c : cats) ids.put(c.id);
        String versionName = "";
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            versionName = info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) {
            // a manifest without a version is still a usable manifest
        }
        return new JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("app", app.getPackageName())
                .put("appVersion", versionName)
                .put("createdTs", System.currentTimeMillis())
                .put("categories", ids)
                .toString(2);
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * One file into the archive. The OCR data and the dictionary are tens of megabytes each, so a
     * cancel is honoured between chunks too, not only between entries.
     */
    private static void writeFileEntry(ZipOutputStream zip, String name, File file, @Nullable Cancel cancel)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                throwIfCancelled(cancel);
                zip.write(buf, 0, n);
            }
        }
        zip.closeEntry();
    }

    private static void throwIfCancelled(@Nullable Cancel cancel) throws CancelledException {
        if (cancel != null && cancel.isCancelled()) throw new CancelledException();
    }

    private static void deleteQuietly(@Nullable DocumentFile doc) {
        try {
            if (doc != null) doc.delete();
        } catch (Exception ignored) {
            // nothing useful is left to do about it
        }
    }

    // ---------------------------------------------------------------------------------------------
    // dump / restore helpers
    // ---------------------------------------------------------------------------------------------

    private static JSONArray names(List<File> files) {
        JSONArray a = new JSONArray();
        for (File f : files) a.put(f.getName());
        return a;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Write a file completely, then move it into place — a crash never leaves half a JSON behind. */
    private static void writeFileAtomic(File target, byte[] data) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cannot write " + target.getName());
        }
    }

    /** Only plain file names travel — an archive cannot write outside the folder it restores into. */
    private static boolean safeName(String name) {
        return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.startsWith(".");
    }

    private static JSONObject dumpPrefs(SharedPreferences sp, @Nullable Set<String> skip) throws JSONException {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String key = e.getKey();
            if (skip != null && skip.contains(key)) continue;
            Object v = e.getValue();
            JSONObject entry = new JSONObject();
            if (v instanceof Boolean) {
                entry.put("t", "bool").put("v", v);
            } else if (v instanceof Integer) {
                entry.put("t", "int").put("v", v);
            } else if (v instanceof Long) {
                entry.put("t", "long").put("v", v);
            } else if (v instanceof Float) {
                entry.put("t", "float").put("v", ((Float) v).doubleValue());
            } else if (v instanceof String) {
                entry.put("t", "string").put("v", v);
            } else if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object s : (Set<?>) v) a.put(String.valueOf(s));
                entry.put("t", "set").put("v", a);
            } else {
                continue;
            }
            obj.put(key, entry);
        }
        return obj;
    }

    private static int mergePrefs(SharedPreferences sp, JSONObject dump, @Nullable Set<String> skip) {
        int n = 0;
        SharedPreferences.Editor editor = sp.edit();
        for (Iterator<String> it = dump.keys(); it.hasNext(); ) {
            String key = it.next();
            if (skip != null && skip.contains(key)) continue;
            JSONObject entry = dump.optJSONObject(key);
            if (entry == null) continue;
            switch (entry.optString("t")) {
                case "bool":
                    editor.putBoolean(key, entry.optBoolean("v"));
                    break;
                case "int":
                    editor.putInt(key, entry.optInt("v"));
                    break;
                case "long":
                    editor.putLong(key, entry.optLong("v"));
                    break;
                case "float":
                    editor.putFloat(key, (float) entry.optDouble("v"));
                    break;
                case "string":
                    editor.putString(key, entry.optString("v"));
                    break;
                case "set": {
                    JSONArray a = entry.optJSONArray("v");
                    Set<String> set = new HashSet<>();
                    if (a != null) for (int i = 0; i < a.length(); i++) set.add(a.optString(i));
                    editor.putStringSet(key, set);
                    break;
                }
                default:
                    continue;
            }
            n++;
        }
        // commit(), NOT apply(): 応用管理 SIGKILLs the app the moment an import answers OK.
        editor.commit();
        return n;
    }

    /** Restore every {@code prefix}-entry of the archive into {@code dir}, atomically, by plain file name. */
    private static int restoreEntries(ZipFile zip, String prefix, File dir, FileFilter accept) throws IOException {
        int n = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();
            if (e.isDirectory() || !name.startsWith(prefix)) continue;
            String file = name.substring(prefix.length());
            if (!safeName(file) || !accept.ok(file)) continue;
            try (InputStream in = zip.getInputStream(e)) {
                writeStreamAtomic(new File(dir, file), in);
            }
            n++;
        }
        return n;
    }

    private interface FileFilter {
        boolean ok(String name);
    }

    /** Stream into a temp file beside the target, then move it into place (large data files). */
    private static void writeStreamAtomic(File target, InputStream in) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cannot write " + target.getName());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // INSPECT + IMPORT
    // ---------------------------------------------------------------------------------------------

    @NonNull
    public static List<String> categoriesIn(@NonNull File file) {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            out.addAll(categoriesIn(zip));
        } catch (Exception ignored) {
            // not a zip, or not ours
        }
        return out;
    }

    @NonNull
    public static List<String> categoriesIn(@NonNull ZipFile zip) {
        List<String> out = new ArrayList<>();
        try {
            String manifest = readEntry(zip, MANIFEST_ENTRY);
            if (manifest == null) return out;
            JSONObject m = new JSONObject(manifest);
            if (!FORMAT.equals(m.optString("format"))) return out;
            for (Cat c : Cat.values()) {
                if (zip.getEntry(c.id + ".json") != null) out.add(c.id);
            }
        } catch (Exception ignored) {
            // a malformed manifest is "not ours"
        }
        return out;
    }

    /**
     * Apply the selected categories from an archive on disk; categories missing from it are
     * skipped. Returns the per-category summary, one line each. Everything is on disk when this
     * returns.
     */
    @NonNull
    public static String importZip(@NonNull Context context, @NonNull File file, @NonNull Set<Cat> cats)
            throws IOException {
        Context app = context.getApplicationContext();
        KakuUi.init(app);
        try (ZipFile zip = new ZipFile(file)) {
            List<String> present = categoriesIn(zip);
            if (present.isEmpty()) throw new IOException(app.getString(R.string.kaku_eim_import_none));
            StringBuilder summary = new StringBuilder();
            boolean any = false;
            for (Cat cat : Cat.values()) {
                if (!cats.contains(cat) || !present.contains(cat.id)) continue;
                String json = readEntry(zip, cat.id + ".json");
                if (json == null) continue;
                String line;
                try {
                    JSONObject o = new JSONObject(json);
                    switch (cat) {
                        case UI: {
                            int keys = 0;
                            JSONObject prefs = o.optJSONObject("prefs");
                            if (prefs != null) {
                                keys = mergePrefs(app.getSharedPreferences(KakuUi.PREFS, Context.MODE_PRIVATE), prefs, null);
                            }
                            int fonts = 0;
                            Enumeration<? extends ZipEntry> entries = zip.entries();
                            while (entries.hasMoreElements()) {
                                ZipEntry e = entries.nextElement();
                                String name = e.getName();
                                if (e.isDirectory() || !name.startsWith(FONTS_PREFIX)) continue;
                                String fontName = name.substring(FONTS_PREFIX.length());
                                if (!safeName(fontName) || !KakuFonts.isFontName(fontName)) continue;
                                try (InputStream in = zip.getInputStream(e)) {
                                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                    byte[] buf = new byte[64 * 1024];
                                    int n;
                                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                                    writeFileAtomic(new File(KakuFonts.dir(app), fontName), bos.toByteArray());
                                }
                                fonts++;
                            }
                            KakuFonts.clearCache();
                            KakuUi.changed();
                            line = app.getString(R.string.kaku_eim_ui_result, keys, fonts);
                            break;
                        }
                        case SETTINGS: {
                            int keys = 0;
                            JSONObject prefs = o.optJSONObject("prefs");
                            if (prefs != null) {
                                keys = mergePrefs(app.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE), prefs, APP_DEVICE_KEYS);
                            }
                            line = app.getString(R.string.kaku_eim_settings_result, keys);
                            break;
                        }
                        case OCR: {
                            int files = restoreEntries(zip, OCR_PREFIX, ocrDir(app), ShiroikumaExport::isOcrName);
                            line = app.getString(R.string.kaku_eim_files_result, files);
                            break;
                        }
                        case DICTIONARY: {
                            shiroikuma.kaku.dict.DictDb.closeInstance();   // the file is replaced under it
                            int files = restoreEntries(zip, DICT_PREFIX, dictDir(app), ShiroikumaExport::isDictName);
                            line = app.getString(R.string.kaku_eim_files_result, files);
                            break;
                        }
                        default:
                            continue;
                    }
                } catch (JSONException e) {
                    throw new IOException(cat.id + ".json: " + e.getMessage(), e);
                }
                any = true;
                if (summary.length() > 0) summary.append('\n');
                summary.append(app.getString(cat.labelRes)).append(": ").append(line);
            }
            if (!any) throw new IOException(app.getString(R.string.kaku_eim_import_nothing));
            return summary.toString();
        }
    }

    @Nullable
    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return readAll(in);
        }
    }
}
