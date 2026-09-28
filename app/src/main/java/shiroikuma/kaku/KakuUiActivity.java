package shiroikuma.kaku;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.util.Locale;

import ca.fuwafuwa.kaku.R;
import shiroikuma.kaku.automation.AutomationAuth;
import shiroikuma.kaku.backup.ShiroikumaExport;
import shiroikuma.kaku.ocr.MangaOcr;
import shiroikuma.kaku.ocr.OcrImport;

/**
 * 白い熊 画 UI — the fork's settings page: every configurable item of the fork, grouped, black-
 * yellow, in the kxkb UI page's visual format (36 / 54 / 72 / 90 dp indents, bold headings
 * underlined as wide as their text, a 1 px rule between top-level groups, tight rows). Opened by a
 * long-press (or a tap) on the Settings cog of the main screen.
 *
 * <p>Built in code, from {@link KakuUi}; every change applies at once and the page repaints in the
 * new colours, so the page itself is the first preview. Each group also ends in its own live preview.
 * The lookup windows repaint the next time they show.
 *
 * <p>The first section is Export / Import (the Kōjiki flow, via {@link ExportImportPanel}).
 */
public class KakuUiActivity extends Activity implements ExportImportPanel.Host {

    private static final int REQ_DIR = 41;
    private static final int REQ_IMPORT = 42;
    private static final int REQ_FONT = 43;
    private static final int REQ_OCR_ENCODER = 44;
    private static final int REQ_OCR_DECODER = 45;
    private static final int REQ_OCR_VOCAB = 46;
    private static final int REQ_OCR_ALL = 47;
    private static final int REQ_DICT = 48;
    private static final int REQ_TESS = 49;

    private static final int IND_HEAD = 36;
    private static final int IND_SUB = 54;
    private static final int IND_L1 = 72;
    private static final int IND_L2 = 90;

    private ScrollView scroll;
    private LinearLayout page;
    private ExportImportPanel panel;
    /** The font slot an import is for (family key), so the imported font is chosen right away. */
    private String pendingFontKey;

    public static void open(@NonNull Context context) {
        Intent i = new Intent(context, KakuUiActivity.class);
        if (!(context instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KakuUi.init(this);
        scroll = new ScrollView(this);
        scroll.setTag(KakuViews.NO_SKIN);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setTag(KakuViews.NO_SKIN);
        page.setPadding(0, 0, 0, dp(32));
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        InsetsKt.applySystemBarPadding(this);
        panel = new ExportImportPanel(this, this);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild(); // the export status is re-queried on every open
    }

    // ---------------------------------------------------------------------------------------------
    // colours of the page itself
    // ---------------------------------------------------------------------------------------------

    private int bg() {
        return KakuUi.i(KakuUi.C_BG);
    }

    private int ink() {
        return KakuUi.i(KakuUi.C_TEXT);
    }

    private int ink2() {
        return KakuUi.i(KakuUi.C_TEXT2);
    }

    private int accent() {
        return KakuUi.i(KakuUi.C_ACCENT);
    }

    private int dp(float v) {
        return KakuViews.dp(this, v);
    }

    // ---------------------------------------------------------------------------------------------
    // the page
    // ---------------------------------------------------------------------------------------------

    private void rebuild() {
        final int y = scroll.getScrollY();
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(bg()));
            w.setStatusBarColor(bg());
            w.setNavigationBarColor(bg());
        }
        scroll.setBackgroundColor(bg());
        page.removeAllViews();

        TextView title = body(KakuFork.NAME + " UI", KakuUi.i(KakuUi.HEAD_FONT_SIZE) + 4, ink(), true, true);
        title.setPadding(dp(IND_HEAD), dp(14), dp(16), dp(4));
        page.addView(title);

        sectionExportImport();
        sectionBehaviour();
        sectionOcr();
        sectionDictionaries();
        sectionCapture();
        sectionCamera();
        sectionColours();
        sectionShapes();
        sectionFonts();
        sectionAbout();

        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ---- 1. Export / Import ----------------------------------------------------------------------

    private void sectionExportImport() {
        heading(getString(R.string.kaku_ui_sec_eximport), true);
        View row = itemRow(IND_L1, getString(R.string.kaku_eim_entry), eximportSummary(null), null, v -> panel.show());
        if (ShiroikumaExport.exportDir(this) != null) {
            // The last-export lookup lists the directory: off the main thread, filled in when known.
            final TextView summary = (TextView) ((LinearLayout) ((LinearLayout) row).getChildAt(0)).getChildAt(1);
            final Context app = getApplicationContext();
            new Thread(() -> {
                final DocumentFile newest = ShiroikumaExport.newestExport(app);
                runOnUiThread(() -> {
                    if (!isFinishing() && summary.isAttachedToWindow()) summary.setText(eximportSummary(newest == null ? NONE : newest));
                });
            }, "kaku-ui-status").start();
        }

        // The 保存復元 gate (sister-app contract v2 §2), below the Export / Import row: the switch
        // defaults ON, the token OFF, and the token row shows only while the token is asked for.
        final Context ctx = this;
        Switch enabled = toggle(AutomationAuth.isEnabled(ctx), on -> AutomationAuth.setEnabled(ctx, on));
        itemRow(IND_L1, getString(R.string.kaku_auto_switch), getString(R.string.kaku_auto_switch_desc), enabled,
                v -> enabled.toggle());

        Switch require = toggle(AutomationAuth.isTokenRequired(ctx), on -> {
            AutomationAuth.setTokenRequired(ctx, on);
            rebuild();
        });
        itemRow(IND_L1, getString(R.string.kaku_auto_require_token), getString(R.string.kaku_auto_require_token_desc),
                require, v -> require.toggle());

        if (AutomationAuth.isTokenRequired(ctx)) {
            View regen = KakuViews.pill(this, getString(R.string.kaku_auto_regenerate), v ->
                    KakuViews.showConfirm(this, getString(R.string.kaku_auto_token_regen_title),
                            getString(R.string.kaku_auto_token_regen_msg), getString(R.string.kaku_auto_regenerate),
                            () -> {
                                AutomationAuth.regenerateToken(ctx);
                                KakuViews.toast(ctx, getString(R.string.kaku_auto_token_regenerated));
                                rebuild();
                            }));
            itemRow(IND_L2, getString(R.string.kaku_auto_token),
                    AutomationAuth.abbreviate(AutomationAuth.token(ctx)) + "\n" + getString(R.string.kaku_auto_token_desc),
                    regen, v -> {
                        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cb != null) {
                            cb.setPrimaryClip(ClipData.newPlainText("automation_token", AutomationAuth.token(ctx)));
                            KakuViews.toast(ctx, getString(R.string.kaku_auto_token_copied));
                        }
                    });
        }
    }

    /** Marker for "looked, found nothing" (null = not looked yet). */
    private static final Object NONE = new Object();

    /**
     * Directory in yellow once set, "not set" in red; then the last export — red when there is
     * none. {@code newest} is null while the lookup is still running.
     */
    private CharSequence eximportSummary(@Nullable Object newest) {
        SpannableStringBuilder sb = new SpannableStringBuilder(getString(R.string.kaku_eim_entry_desc));
        sb.append('\n');
        String dir = ShiroikumaExport.dirLabel(this);
        int start = sb.length();
        sb.append(getString(R.string.kaku_eim_dir)).append(": ")
                .append(dir != null ? dir : getString(R.string.kaku_eim_dir_unset));
        sb.setSpan(new ForegroundColorSpan(dir != null ? ink() : KakuUi.WARN), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n');
        start = sb.length();
        boolean warn;
        if (dir == null) {
            sb.append(getString(R.string.kaku_eim_warn_nodir));
            warn = true;
        } else if (newest == null) {
            sb.append(getString(R.string.kaku_eim_last_checking));
            warn = false;
        } else if (!(newest instanceof DocumentFile)) {
            sb.append(getString(R.string.kaku_eim_warn_none));
            warn = true;
        } else {
            DocumentFile f = (DocumentFile) newest;
            long ts = f.lastModified();
            sb.append(getString(R.string.kaku_eim_last_line, DateFormat.getDateFormat(this).format(ts) + " "
                    + DateFormat.getTimeFormat(this).format(ts) + " · " + ShiroikumaExport.humanSize(f.length())));
            warn = false;
        }
        sb.setSpan(new ForegroundColorSpan(warn ? KakuUi.WARN : ink()), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // ---- 2. Behaviour -----------------------------------------------------------------------------

    private void sectionBehaviour() {
        heading(getString(R.string.kaku_ui_sec_behaviour), false);
        itemRow(IND_L1, getString(R.string.kaku_ui_reset), getString(R.string.kaku_ui_reset_desc), null,
                v -> KakuViews.showConfirm(this, getString(R.string.kaku_ui_reset),
                        getString(R.string.kaku_ui_reset_confirm), getString(R.string.kaku_ui_reset_action), () -> {
                            KakuUi.reset();
                            rebuild();
                        }));
    }

    // ---- OCR -----------------------------------------------------------------------------------------

    private void sectionOcr() {
        heading(getString(R.string.kaku_ocr_sec), false);

        sub(getString(R.string.kaku_ocr_sub_engine));
        final boolean manga = KakuUi.ENGINE_MANGAOCR.equals(KakuUi.s(KakuUi.OCR_ENGINE));
        final boolean installed = MangaOcr.INSTANCE.installed(this);
        itemRow(IND_L2, getString(R.string.kaku_ocr_engine_manga), getString(R.string.kaku_ocr_engine_manga_desc),
                check(manga), v -> {
                    KakuUi.set(KakuUi.OCR_ENGINE, KakuUi.ENGINE_MANGAOCR);
                    rebuild();
                });
        itemRow(IND_L2, getString(R.string.kaku_ocr_engine_tess), getString(R.string.kaku_ocr_engine_tess_desc),
                check(!manga), v -> {
                    KakuUi.set(KakuUi.OCR_ENGINE, KakuUi.ENGINE_TESSERACT);
                    rebuild();
                });
        if (manga && !installed) warnNote(getString(R.string.kaku_ocr_manga_missing));

        sub(getString(R.string.kaku_ocr_sub_manga));
        note(getString(R.string.kaku_ocr_manga_note));
        ocrFileRow(R.string.kaku_ocr_file_encoder, MangaOcr.ENCODER, MangaOcr.URL_ENCODER, REQ_OCR_ENCODER);
        ocrFileRow(R.string.kaku_ocr_file_decoder, MangaOcr.DECODER, MangaOcr.URL_DECODER, REQ_OCR_DECODER);
        ocrFileRow(R.string.kaku_ocr_file_vocab, MangaOcr.VOCAB, MangaOcr.URL_VOCAB, REQ_OCR_VOCAB);
        itemRow(IND_L2, getString(R.string.kaku_ocr_import_all), getString(R.string.kaku_ocr_import_all_desc), null, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_OCR_ALL);
        });

        sub(getString(R.string.kaku_ocr_sub_tess));
        java.io.File tess = OcrImport.INSTANCE.tesseractFile(this);
        boolean tessPresent = tess.isFile() && tess.length() > 0;
        final String tessUrl = "https://github.com/tesseract-ocr/tessdata/raw/main/jpn.traineddata";
        SpannableStringBuilder ts = new SpannableStringBuilder();
        ts.append(tessPresent ? getString(R.string.kaku_ocr_file_present, ShiroikumaExport.humanSize(tess.length()))
                : getString(R.string.kaku_ocr_file_missing));
        ts.setSpan(new ForegroundColorSpan(tessPresent ? ink() : KakuUi.WARN), 0, ts.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ts.append('\n').append(tessUrl);
        View copy = KakuViews.pill(this, getString(R.string.kaku_ocr_copy_url), v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("url", tessUrl));
                KakuViews.toast(this, getString(R.string.kaku_ocr_url_copied));
            }
        });
        itemRow(IND_L2, getString(R.string.kaku_ocr_file_tess), ts, copy, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_TESS);
        });
    }

    private void importTesseract(final Uri uri) {
        KakuViews.toast(this, getString(R.string.kaku_ocr_importing));
        final Context app = getApplicationContext();
        new Thread(() -> {
            String body;
            boolean ok;
            try {
                long bytes = OcrImport.INSTANCE.importTesseract(app, uri);
                body = getString(R.string.kaku_ocr_import_ok, "jpn.traineddata", ShiroikumaExport.humanSize(bytes));
                ok = true;
            } catch (Exception e) {
                body = String.valueOf(e.getMessage());
                ok = false;
            }
            final String b = body;
            final boolean good = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                rebuild();
                KakuViews.showInfo(this, getString(good ? R.string.kaku_ocr_import_done_title : R.string.kaku_ocr_import_fail_title), b, true, null);
            });
        }, "kaku-tess-import").start();
    }

    /** A ✓ in the accent colour for the chosen option, an empty slot of the same width otherwise. */
    private View check(boolean on) {
        TextView tv = body(on ? "✓" : "", 20, accent(), false, true);
        tv.setGravity(Gravity.CENTER);
        tv.setMinWidth(dp(28));
        return tv;
    }

    private void warnNote(String text) {
        TextView tv = body(text, 13, KakuUi.WARN, false, false);
        tv.setPadding(dp(IND_L2), dp(2), dp(16), dp(2));
        page.addView(tv);
    }

    /**
     * One MangaOCR file: installed with its size (yellow) or not imported (red), then where to get
     * it — the Copy URL pill puts the address on the clipboard for a browser; a tap imports.
     */
    private void ocrFileRow(int titleRes, final String name, final String url, final int req) {
        java.io.File f = MangaOcr.INSTANCE.file(this, name);
        boolean present = f.isFile() && f.length() > 0;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int start = sb.length();
        sb.append(present ? getString(R.string.kaku_ocr_file_present, ShiroikumaExport.humanSize(f.length()))
                : getString(R.string.kaku_ocr_file_missing));
        sb.setSpan(new ForegroundColorSpan(present ? ink() : KakuUi.WARN), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n').append(url);
        View copy = KakuViews.pill(this, getString(R.string.kaku_ocr_copy_url), v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("url", url));
                KakuViews.toast(this, getString(R.string.kaku_ocr_url_copied));
            }
        });
        itemRow(IND_L2, getString(titleRes), sb, copy, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, req);
        });
    }

    /** Copy picked files in, off the main thread; the page repaints with the new status. */
    private void importOcrFiles(final java.util.List<Uri> uris, @Nullable final String forcedTarget) {
        KakuViews.toast(this, getString(R.string.kaku_ocr_importing));
        final Context app = getApplicationContext();
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            boolean failed = false;
            for (Uri uri : uris) {
                String picked = OcrImport.INSTANCE.displayName(app, uri);
                String target = forcedTarget != null ? forcedTarget : OcrImport.INSTANCE.classify(picked);
                if (report.length() > 0) report.append('\n');
                if (target == null) {
                    failed = true;
                    report.append(getString(R.string.kaku_ocr_import_unknown, String.valueOf(picked)));
                    continue;
                }
                try {
                    long bytes = OcrImport.INSTANCE.importFile(app, uri, target);
                    report.append(getString(R.string.kaku_ocr_import_ok, target, ShiroikumaExport.humanSize(bytes)));
                } catch (Exception e) {
                    failed = true;
                    report.append(target).append(": ").append(e.getMessage());
                }
            }
            final boolean anyFailed = failed;
            final String body = report.toString();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                rebuild();
                KakuViews.showInfo(this, getString(anyFailed ? R.string.kaku_ocr_import_fail_title : R.string.kaku_ocr_import_done_title),
                        body, true, null);
            });
        }, "kaku-ocr-import").start();
    }

    // ---- Dictionaries ------------------------------------------------------------------------------

    /** The line under the heading that follows a running import, updated in place. */
    @Nullable
    private TextView importStatus;

    private final shiroikuma.kaku.dict.DictImportService.Listener importListener = new shiroikuma.kaku.dict.DictImportService.Listener() {
        @Override
        public void onProgress(@NonNull shiroikuma.kaku.dict.DictImport.Progress p) {
            if (importStatus != null) importStatus.setText(importLine(p));
            if (!importDialogHidden) showImportDialog();
            updateImportDialog(p);
        }

        @Override
        public void onFinished(@NonNull String report) {
            rebuild();
            finishImportDialog(report);
        }
    };

    // ---- the import dialog: live progress on the page itself ----------------------------------

    @Nullable private android.app.AlertDialog importDialog;
    @Nullable private TextView importDialogTitle;
    @Nullable private TextView importDialogDetail;
    @Nullable private android.widget.ProgressBar importDialogBar;
    @Nullable private LinearLayout importDialogButtons;
    /** 「In the background」 was pressed: the import goes on (its notification stays), the dialog stays shut. */
    private boolean importDialogHidden;

    /**
     * The running import as a bordered dialog: which dictionary, what it is doing (reading the file,
     * rows and bank n/m, building the index) and a progress bar over the banks. 「In the background」
     * closes it (the import and its notification go on); when the import ends, the dialog turns into
     * its result with OK.
     */
    private void showImportDialog() {
        if (importDialog != null && importDialog.isShowing()) return;
        LinearLayout box = KakuViews.infoBox(this, getString(R.string.dict_import_title), "");
        // infoBox's body line (child 1) becomes the dictionary line; a detail line and the bar follow.
        importDialogTitle = (TextView) box.getChildAt(1);
        importDialogDetail = KakuViews.text(this, "", 14, KakuViews.ink(), false);
        importDialogDetail.setPadding(0, dp(6), 0, 0);
        box.addView(importDialogDetail);
        importDialogBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importDialogBar.setIndeterminate(true);
        importDialogBar.setProgressTintList(ColorStateList.valueOf(accent()));
        importDialogBar.setIndeterminateTintList(ColorStateList.valueOf(accent()));
        importDialogBar.setProgressBackgroundTintList(ColorStateList.valueOf(ink2()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        lp.topMargin = dp(12);
        box.addView(importDialogBar, lp);
        importDialogButtons = KakuViews.buttonRow(this);
        final android.app.AlertDialog dialog = KakuViews.boxDialog(this, box, false);
        importDialogButtons.addView(KakuViews.pill(this, getString(R.string.dict_import_background), v -> {
            importDialogHidden = true;
            dialog.dismiss();
        }));
        box.addView(importDialogButtons);
        importDialog = dialog;
        dialog.show();
        KakuViews.transparentWindow(dialog);
        shiroikuma.kaku.dict.DictImport.Progress p = shiroikuma.kaku.dict.DictImportService.Companion.getCurrent();
        if (p != null) updateImportDialog(p);
        else if (importDialogTitle != null) importDialogTitle.setText(R.string.dict_import_starting);
    }

    private void updateImportDialog(@NonNull shiroikuma.kaku.dict.DictImport.Progress p) {
        if (importDialog == null || !importDialog.isShowing()) return;
        if (importDialogTitle != null) {
            importDialogTitle.setText(p.getTitle().isEmpty() ? getString(R.string.dict_import_copying) : p.getTitle());
        }
        if (importDialogDetail != null) {
            switch (p.getPhase()) {
                case "copy": importDialogDetail.setText(R.string.dict_import_phase_copy); break;
                case "index": importDialogDetail.setText(R.string.dict_import_phase_index); break;
                case "media": importDialogDetail.setText(getString(R.string.dict_import_phase_media, p.getBank(), p.getBanks())); break;
                default: importDialogDetail.setText(getString(R.string.dict_import_phase_rows, p.getRows(), p.getBank(), p.getBanks()));
            }
        }
        if (importDialogBar != null) {
            boolean known = ("rows".equals(p.getPhase()) || "media".equals(p.getPhase())) && p.getBanks() > 0;
            importDialogBar.setIndeterminate(!known);
            if (known) {
                importDialogBar.setMax(p.getBanks());
                importDialogBar.setProgress(p.getBank());
            }
        }
    }

    /** The import ended: the open dialog becomes the result (or a new one says it, if it was hidden). */
    private void finishImportDialog(@NonNull String report) {
        importDialogHidden = false;
        final android.app.AlertDialog dialog = importDialog;
        if (dialog == null || !dialog.isShowing() || importDialogButtons == null) {
            KakuViews.showInfo(this, getString(R.string.dict_import_title), report, true, null);
            return;
        }
        if (importDialogTitle != null) importDialogTitle.setText(R.string.dict_import_done);
        if (importDialogDetail != null) importDialogDetail.setText(report);
        if (importDialogBar != null) importDialogBar.setVisibility(View.GONE);
        importDialogButtons.removeAllViews();
        importDialogButtons.addView(KakuViews.pill(this, getString(R.string.kaku_eim_ok), v -> dialog.dismiss()));
        dialog.setCancelable(true);
    }

    private String importLine(shiroikuma.kaku.dict.DictImport.Progress p) {
        switch (p.getPhase()) {
            case "copy": return getString(R.string.dict_import_copying);
            case "index": return getString(R.string.dict_import_indexing, p.getTitle());
            case "media": return getString(R.string.dict_import_media, p.getTitle(), p.getBank(), p.getBanks());
            default: return getString(R.string.dict_import_rows, p.getTitle(), p.getRows(), p.getBank(), p.getBanks());
        }
    }

    private void sectionDictionaries() {
        heading(getString(R.string.dict_sec), false);
        final shiroikuma.kaku.dict.DictDb db = shiroikuma.kaku.dict.DictDb.get(this);
        java.util.List<shiroikuma.kaku.dict.DictDb.Dictionary> dicts = db.dictionaries();
        boolean yomitan = db.hasEnabledTerms();
        TextView engine = body(getString(yomitan ? R.string.dict_engine_yomitan : R.string.dict_engine_bundled), 13,
                yomitan ? ink() : KakuUi.WARN, false, false);
        engine.setPadding(dp(IND_L1), dp(2), dp(16), dp(4));
        page.addView(engine);

        shiroikuma.kaku.dict.DictImport.Progress running = shiroikuma.kaku.dict.DictImportService.Companion.getCurrent();
        importStatus = null;
        if (running != null) {
            importStatus = body(importLine(running), 13, accent(), false, true);
            importStatus.setPadding(dp(IND_L1), dp(2), dp(16), dp(4));
            page.addView(importStatus);
        }

        for (final shiroikuma.kaku.dict.DictDb.Dictionary d : dicts) {
            StringBuilder sum = new StringBuilder();
            if (d.getTerms() > 0) sum.append(getString(R.string.dict_count_terms, d.getTerms()));
            if (d.getKanji() > 0) sum.append(sum.length() > 0 ? " · " : "").append(getString(R.string.dict_count_kanji, d.getKanji()));
            if (d.getTermMeta() > 0) sum.append(sum.length() > 0 ? " · " : "").append(getString(R.string.dict_count_meta, d.getTermMeta()));
            if (!d.getComplete()) sum.append(sum.length() > 0 ? " · " : "").append(getString(R.string.dict_incomplete));
            Switch on = toggle(d.getEnabled(), v -> { db.setEnabled(d.getId(), v); rebuild(); });
            itemRow(IND_L2, d.getTitle() + (d.getRevision().isEmpty() ? "" : "  [" + d.getRevision() + "]"), sum, on,
                    v -> dictionaryOptions(d));
        }

        itemRow(IND_L1, getString(R.string.dict_import), getString(R.string.dict_import_desc), null, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_DICT);
        });

        sub(getString(R.string.dict_sub_where));
        note(getString(R.string.dict_where_note));
        dictUrlRow(R.string.dict_url_jmdict, "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip");
        dictUrlRow(R.string.dict_url_kanjidic, "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip");
        dictUrlRow(R.string.dict_url_jmnedict, "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip");
        dictUrlRow(R.string.dict_url_jitendex, "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip");
        dictUrlRow(R.string.dict_url_jpdb, "https://github.com/Kuuuube/yomitan-dictionaries/releases/download/yomitan-permalink/JPDB_v2.2_Frequency_Kana.zip");
        dictUrlRow(R.string.dict_url_bccwj, "https://github.com/Kuuuube/yomitan-dictionaries/releases/download/yomitan-permalink/BCCWJ_SUW_LUW_combined.zip");
        dictUrlRow(R.string.dict_url_more, "https://github.com/MarvNC/yomitan-dictionaries");
    }

    private void dictUrlRow(int titleRes, final String url) {
        View copy = KakuViews.pill(this, getString(R.string.kaku_ocr_copy_url), v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("url", url));
                KakuViews.toast(this, getString(R.string.kaku_ocr_url_copied));
            }
        });
        itemRow(IND_L2, getString(titleRes), url, copy, null);
    }

    /** Move up / Move down / Delete for one dictionary, as a bordered dialog of pills. */
    private void dictionaryOptions(final shiroikuma.kaku.dict.DictDb.Dictionary d) {
        final shiroikuma.kaku.dict.DictDb db = shiroikuma.kaku.dict.DictDb.get(this);
        LinearLayout box = KakuViews.infoBox(this, d.getTitle(),
                (d.getAttribution() == null ? "" : d.getAttribution() + "\n\n") + getString(R.string.dict_options_desc));
        final android.app.AlertDialog dialog = KakuViews.boxDialog(this, box, true);
        LinearLayout row = KakuViews.buttonRow(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(KakuViews.pill(this, getString(R.string.dict_up), v -> { db.move(d.getId(), -1); dialog.dismiss(); rebuild(); }));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(8), 0));
        row.addView(KakuViews.pill(this, getString(R.string.dict_down), v -> { db.move(d.getId(), 1); dialog.dismiss(); rebuild(); }));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
        row.addView(KakuViews.pill(this, getString(R.string.dict_delete), v -> {
            dialog.dismiss();
            KakuViews.showConfirm(this, getString(R.string.dict_delete_title, d.getTitle()), getString(R.string.dict_delete_msg),
                    getString(R.string.dict_delete), () -> { db.delete(d.getId()); rebuild(); });
        }));
        box.addView(row);
        dialog.show();
        KakuViews.transparentWindow(dialog);
    }

    @Override
    protected void onStart() {
        super.onStart();
        shiroikuma.kaku.dict.DictImportService.Companion.getListeners().add(importListener);
        // Opened (or come back to) while an import runs: its dialog is there at once.
        if (shiroikuma.kaku.dict.DictImportService.Companion.getCurrent() != null && !importDialogHidden) {
            scroll.post(this::showImportDialog);
        }
    }

    @Override
    protected void onStop() {
        shiroikuma.kaku.dict.DictImportService.Companion.getListeners().remove(importListener);
        super.onStop();
    }

    // ---- Capture ---------------------------------------------------------------------------------

    /** The capture box's own switches (upstream's notification toggles), in the app's settings file. */
    private void sectionCapture() {
        heading(getString(R.string.kaku_cap_sec), false);
        final android.content.SharedPreferences prefs = getSharedPreferences(ca.fuwafuwa.kaku.Constants.KAKU_PREF_FILE, MODE_PRIVATE);
        Switch instant = toggle(prefs.getBoolean(ca.fuwafuwa.kaku.Constants.KAKU_PREF_INSTANT_MODE, true), on -> {
            prefs.edit().putBoolean(ca.fuwafuwa.kaku.Constants.KAKU_PREF_INSTANT_MODE, on).apply();
            refreshService();
        });
        itemRow(IND_L1, getString(R.string.kaku_cap_instant), getString(R.string.kaku_cap_instant_desc), instant, v -> instant.toggle());
        Switch filter = toggle(prefs.getBoolean(ca.fuwafuwa.kaku.Constants.KAKU_PREF_IMAGE_FILTER, true), on -> {
            prefs.edit().putBoolean(ca.fuwafuwa.kaku.Constants.KAKU_PREF_IMAGE_FILTER, on).apply();
            refreshService();
        });
        itemRow(IND_L1, getString(R.string.kaku_cap_filter), getString(R.string.kaku_cap_filter_desc), filter, v -> filter.toggle());

        sub(getString(R.string.kaku_cap_direction));
        String dir = prefs.getString(ca.fuwafuwa.kaku.Constants.KAKU_PREF_TEXT_DIRECTION, ca.fuwafuwa.kaku.TextDirection.AUTO.toString());
        for (ca.fuwafuwa.kaku.TextDirection d : ca.fuwafuwa.kaku.TextDirection.values()) {
            int label = d == ca.fuwafuwa.kaku.TextDirection.AUTO ? R.string.kaku_cap_dir_auto
                    : d == ca.fuwafuwa.kaku.TextDirection.HORIZONTAL ? R.string.kaku_cap_dir_h : R.string.kaku_cap_dir_v;
            itemRow(IND_L2, getString(label), null, check(d.toString().equals(dir)), v -> {
                prefs.edit().putString(ca.fuwafuwa.kaku.Constants.KAKU_PREF_TEXT_DIRECTION, d.toString()).apply();
                refreshService();
                rebuild();
            });
        }
        note(getString(R.string.kaku_cap_direction_note));
    }

    /** A running capture service re-reads its settings (and its notification) on a start command. */
    private void refreshService() {
        if (ca.fuwafuwa.kaku.MainService.IsRunning()) {
            ca.fuwafuwa.kaku.KakuTools.startKakuService(this, new Intent(this, ca.fuwafuwa.kaku.MainService.class));
        }
    }

    // ---- Camera ----------------------------------------------------------------------------------

    private void sectionCamera() {
        heading(getString(R.string.kaku_cam_sec), false);
        itemRow(IND_L1, getString(R.string.kaku_cam_open), getString(R.string.kaku_cam_open_desc), null,
                v -> shiroikuma.kaku.camera.CameraActivity.open(this));
        Switch icon = toggle(KakuUi.b(KakuUi.CAM_LAUNCHER), on -> {
            KakuUi.set(KakuUi.CAM_LAUNCHER, on);
            shiroikuma.kaku.camera.CameraActivity.applyLauncherIcon(this);
        });
        itemRow(IND_L1, getString(R.string.kaku_cam_icon), getString(R.string.kaku_cam_icon_desc), icon, v -> icon.toggle());
        Switch live = toggle(KakuUi.b(KakuUi.CAM_LIVE), on -> KakuUi.set(KakuUi.CAM_LIVE, on));
        itemRow(IND_L1, getString(R.string.kaku_cam_live), getString(R.string.kaku_cam_live_desc), live, v -> live.toggle());
        slider(R.string.kaku_cam_settle, KakuUi.CAM_LIVE_SETTLE, 250, 3000, 250,
                v -> String.format(Locale.ROOT, "%.2f s", v / 1000f), null);
        note(getString(R.string.kaku_cam_routes));
    }

    // ---- 3. Colours --------------------------------------------------------------------------------

    private void sectionColours() {
        heading(getString(R.string.kaku_ui_sec_colours), false);

        sub(getString(R.string.kaku_ui_sub_screens));
        colorRow(R.string.kaku_ui_c_bg, KakuUi.C_BG);
        colorRow(R.string.kaku_ui_c_text, KakuUi.C_TEXT);
        colorRow(R.string.kaku_ui_c_text2, KakuUi.C_TEXT2);
        colorRow(R.string.kaku_ui_c_accent, KakuUi.C_ACCENT);
        preview(screenPreview());

        sub(getString(R.string.kaku_ui_sub_windows));
        colorRow(R.string.kaku_ui_c_win_bg, KakuUi.C_WIN_BG);
        colorRow(R.string.kaku_ui_c_win_border, KakuUi.C_WIN_BORDER);
        preview(windowPreview());

        sub(getString(R.string.kaku_ui_sub_dict));
        colorRow(R.string.kaku_ui_c_dict_headword, KakuUi.C_DICT_HEADWORD);
        colorRow(R.string.kaku_ui_c_dict_reading, KakuUi.C_DICT_READING);
        colorRow(R.string.kaku_ui_c_dict_pos, KakuUi.C_DICT_POS);
        colorRow(R.string.kaku_ui_c_dict_text, KakuUi.C_DICT_TEXT);
        preview(windowPreview());

        sub(getString(R.string.kaku_ui_sub_chars));
        colorRow(R.string.kaku_ui_c_char_text, KakuUi.C_CHAR_TEXT);
        colorRow(R.string.kaku_ui_c_char_hl_fill, KakuUi.C_CHAR_HL_FILL);
        colorRow(R.string.kaku_ui_c_char_hl_border, KakuUi.C_CHAR_HL_BORDER);
        colorRow(R.string.kaku_ui_c_icon, KakuUi.C_ICON);
        preview(charsPreview());

        sub(getString(R.string.kaku_ui_sub_choice));
        colorRow(R.string.kaku_ui_c_choice_bg, KakuUi.C_CHOICE_BG);
        colorRow(R.string.kaku_ui_c_choice_border, KakuUi.C_CHOICE_BORDER);
        colorRow(R.string.kaku_ui_c_choice_active, KakuUi.C_CHOICE_ACTIVE);
        colorRow(R.string.kaku_ui_c_choice_kanji, KakuUi.C_CHOICE_KANJI);
        colorRow(R.string.kaku_ui_c_choice_hiragana, KakuUi.C_CHOICE_HIRAGANA);
        colorRow(R.string.kaku_ui_c_choice_katakana, KakuUi.C_CHOICE_KATAKANA);
        colorRow(R.string.kaku_ui_c_choice_other, KakuUi.C_CHOICE_OTHER);
        preview(choicePreview());

        sub(getString(R.string.kaku_ui_sub_capture));
        colorRow(R.string.kaku_ui_c_capture_border, KakuUi.C_CAPTURE_BORDER);
        colorRow(R.string.kaku_ui_c_capture_busy, KakuUi.C_CAPTURE_BUSY);
        note(getString(R.string.kaku_ui_capture_note));
        preview(capturePreview());

        sub(getString(R.string.kaku_ui_sub_edit));
        colorRow(R.string.kaku_ui_c_edit_bg, KakuUi.C_EDIT_BG);
        colorRow(R.string.kaku_ui_c_edit_text, KakuUi.C_EDIT_TEXT);
        preview(editPreview());

        sub(getString(R.string.kaku_ui_sub_dialogs));
        colorRow(R.string.kaku_ui_c_dlg_bg, KakuUi.C_DLG_BG);
        colorRow(R.string.kaku_ui_c_dlg_text, KakuUi.C_DLG_TEXT);
        colorRow(R.string.kaku_ui_c_dlg_border, KakuUi.C_DLG_BORDER);
        preview(dialogPreview());
    }

    // ---- 4. Borders & shapes -----------------------------------------------------------------------

    private void sectionShapes() {
        heading(getString(R.string.kaku_ui_sec_shapes), false);

        sub(getString(R.string.kaku_ui_sub_windows));
        final View[] winPv = new View[1];
        slider(R.string.kaku_ui_border_w, KakuUi.WIN_BORDER_W, 0, 80, 5, this::tenthsDp, () -> refreshWindow(winPv[0]));
        slider(R.string.kaku_ui_radius, KakuUi.WIN_RADIUS, 0, 40, 1, v -> v + " dp", () -> refreshWindow(winPv[0]));
        slider(R.string.kaku_ui_win_alpha, KakuUi.WIN_ALPHA, 20, 100, 5, v -> v + " %", () -> refreshWindow(winPv[0]));
        winPv[0] = windowPreview();
        preview(winPv[0]);

        sub(getString(R.string.kaku_ui_sub_chars));
        final View[] charPv = new View[1];
        slider(R.string.kaku_ui_hl_border_w, KakuUi.CHAR_HL_BORDER_W, 0, 40, 5, this::tenthsDp, () -> refreshChars(charPv[0]));
        charPv[0] = charsPreview();
        preview(charPv[0]);

        sub(getString(R.string.kaku_ui_sub_dialogs));
        final View[] dlgPv = new View[1];
        slider(R.string.kaku_ui_border_w, KakuUi.DLG_BORDER_W, 0, 80, 5, this::tenthsDp, () -> refreshDialog(dlgPv[0]));
        slider(R.string.kaku_ui_radius, KakuUi.DLG_RADIUS, 0, 40, 1, v -> v + " dp", () -> refreshDialog(dlgPv[0]));
        dlgPv[0] = dialogPreview();
        preview(dlgPv[0]);
    }

    private String tenthsDp(int tenths) {
        return String.format(Locale.ROOT, "%.1f dp", tenths / 10f);
    }

    // ---- 5. Fonts --------------------------------------------------------------------------------

    private void sectionFonts() {
        heading(getString(R.string.kaku_ui_sec_fonts), false);

        sub(getString(R.string.kaku_ui_sub_dict));
        final View[] dictPv = new View[1];
        fontRow(KakuUi.DICT_FONT_FAMILY, KakuUi.DICT_FONT_WEIGHT);
        slider(R.string.kaku_ui_font_weight, KakuUi.DICT_FONT_WEIGHT, 100, 900, 100, String::valueOf,
                () -> refreshWindow(dictPv[0]));
        slider(R.string.kaku_ui_font_size, KakuUi.DICT_FONT_SIZE, 10, 32, 1, v -> v + " sp",
                () -> refreshWindow(dictPv[0]));
        slider(R.string.kaku_ui_head_scale, KakuUi.DICT_HEAD_SCALE, 100, 200, 10, v -> v + " %",
                () -> refreshWindow(dictPv[0]));
        slider(R.string.kaku_ui_dict_zoom, KakuUi.DICT_ZOOM, DictWebView.ZOOM_MIN, DictWebView.ZOOM_MAX, 10, v -> v + " %",
                () -> refreshWindow(dictPv[0]));
        dictPv[0] = windowPreview();
        preview(dictPv[0]);

        sub(getString(R.string.kaku_ui_sub_chars));
        final View[] charPv = new View[1];
        fontRow(KakuUi.CHAR_FONT_FAMILY, KakuUi.CHAR_FONT_WEIGHT);
        slider(R.string.kaku_ui_font_weight, KakuUi.CHAR_FONT_WEIGHT, 100, 900, 100, String::valueOf,
                () -> refreshChars(charPv[0]));
        slider(R.string.kaku_ui_font_size, KakuUi.CHAR_FONT_SIZE, 12, 72, 1, v -> v + " dp",
                () -> refreshChars(charPv[0]));
        charPv[0] = charsPreview();
        preview(charPv[0]);

        sub(getString(R.string.kaku_ui_sub_ui_text));
        final TextView[] uiPv = new TextView[1];
        fontRow(KakuUi.FONT_FAMILY, KakuUi.FONT_WEIGHT);
        slider(R.string.kaku_ui_font_weight, KakuUi.FONT_WEIGHT, 100, 900, 100, String::valueOf,
                () -> styleUiSample(uiPv[0]));
        slider(R.string.kaku_ui_font_scale, KakuUi.FONT_SCALE, 70, 160, 5, v -> v + " %",
                () -> styleUiSample(uiPv[0]));
        uiPv[0] = sample();
        styleUiSample(uiPv[0]);
        preview(uiPv[0]);

        sub(getString(R.string.kaku_ui_sub_headings));
        final TextView[] headPv = new TextView[1];
        fontRow(KakuUi.HEAD_FONT_FAMILY, KakuUi.HEAD_FONT_WEIGHT);
        slider(R.string.kaku_ui_font_weight, KakuUi.HEAD_FONT_WEIGHT, 100, 900, 100, String::valueOf,
                () -> styleHeadSample(headPv[0]));
        slider(R.string.kaku_ui_font_size, KakuUi.HEAD_FONT_SIZE, 12, 36, 1, v -> v + " sp",
                () -> styleHeadSample(headPv[0]));
        headPv[0] = sample();
        headPv[0].setText(R.string.kaku_ui_head_sample);
        styleHeadSample(headPv[0]);
        preview(headPv[0]);

        itemRow(IND_L1, getString(R.string.kaku_font_import_row), getString(R.string.kaku_font_import_desc), null,
                v -> importFont(null));
    }

    private TextView sample() {
        TextView tv = new TextView(this);
        tv.setText(R.string.kaku_font_sample);
        tv.setTag(KakuViews.NO_SKIN);
        return tv;
    }

    private void styleUiSample(TextView tv) {
        if (tv == null) return;
        tv.setTextColor(ink());
        tv.setTypeface(KakuFonts.at(this, KakuUi.s(KakuUi.FONT_FAMILY), KakuUi.i(KakuUi.FONT_WEIGHT), false));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * KakuUi.i(KakuUi.FONT_SCALE) / 100f);
    }

    private void styleHeadSample(TextView tv) {
        if (tv == null) return;
        tv.setTextColor(ink());
        tv.setTypeface(KakuFonts.at(this, KakuUi.s(KakuUi.HEAD_FONT_FAMILY), KakuUi.i(KakuUi.HEAD_FONT_WEIGHT), false));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, KakuUi.i(KakuUi.HEAD_FONT_SIZE));
    }

    // ---- 6. About -----------------------------------------------------------------------------------

    private void sectionAbout() {
        heading(getString(R.string.kaku_ui_sec_about), false);
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // shown without a version
        }
        itemRow(IND_L1, KakuFork.NAME + " " + version, KakuFork.GITHUB, null,
                v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(KakuFork.GITHUB))));
    }

    // ---------------------------------------------------------------------------------------------
    // row builders (kxkb layout numbers)
    // ---------------------------------------------------------------------------------------------

    private TextView body(CharSequence s, float sizeSp, int color, boolean heading, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (heading) {
            tv.setTypeface(KakuFonts.at(this, KakuUi.s(KakuUi.HEAD_FONT_FAMILY),
                    bold ? KakuUi.i(KakuUi.HEAD_FONT_WEIGHT) : 400, false));
        } else {
            tv.setTypeface(KakuFonts.at(this, KakuUi.s(KakuUi.FONT_FAMILY),
                    bold ? Math.max(700, KakuUi.i(KakuUi.FONT_WEIGHT)) : KakuUi.i(KakuUi.FONT_WEIGHT), false));
        }
        tv.setTag(KakuViews.NO_SKIN);
        return tv;
    }

    /** Top-level heading: a 1px rule (not above the first), then the bold title underlined as wide as its text. */
    private void heading(String title, boolean first) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(0, first ? dp(6) : dp(10), 0, dp(2));
        if (!first) {
            View rule = new View(this);
            rule.setBackgroundColor(ink());
            outer.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        outer.addView(underlined(title, KakuUi.i(KakuUi.HEAD_FONT_SIZE), 2.5f, IND_HEAD, 8));
        page.addView(outer);
    }

    private void sub(String title) {
        View v = underlined(title, Math.max(10, KakuUi.i(KakuUi.HEAD_FONT_SIZE) - 3), 1.5f, IND_SUB, 10);
        page.addView(v);
    }

    private View underlined(String title, float sizeSp, float lineDp, int indentDp, int topDp) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(indentDp), dp(topDp), dp(16), dp(2));
        // wrap_content inner box: the underline (match_parent inside it) is exactly as wide as the text
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(body(title, sizeSp, ink(), true, true));
        View line = new View(this);
        line.setBackgroundColor(ink());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(lineDp)));
        lp.topMargin = dp(2);
        inner.addView(line, lp);
        wrap.addView(inner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** A dim explanatory line, indented like the rows around it. */
    private void note(String text) {
        TextView tv = body(text, 13, ink2(), false, false);
        tv.setPadding(dp(IND_L2), dp(2), dp(16), dp(2));
        page.addView(tv);
    }

    private View itemRow(int indentDp, CharSequence title, @Nullable CharSequence summary, @Nullable View widget,
                         @Nullable View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(indentDp), dp(5), dp(16), dp(5));
        row.setTag(KakuViews.NO_SKIN);
        if (onClick != null) {
            row.setClickable(true);
            row.setOnClickListener(onClick);
            row.setBackground(ripple());
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(body(title, 16, ink(), false, false));
        if (summary != null && summary.length() > 0) texts.addView(body(summary, 13, ink2(), false, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (widget != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(10);
            row.addView(widget, lp);
        }
        page.addView(row);
        return row;
    }

    private android.graphics.drawable.RippleDrawable ripple() {
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf((ink() & 0x00FFFFFF) | 0x33000000), null, new ColorDrawable(Color.WHITE));
    }

    interface OnToggle {
        void on(boolean value);
    }

    private Switch toggle(boolean value, OnToggle onToggle) {
        Switch s = new Switch(this);
        s.setChecked(value);
        s.setTag(KakuViews.NO_SKIN);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{accent(), ink2()}));
        s.setTrackTintList(new ColorStateList(states, new int[]{(accent() & 0x00FFFFFF) | 0x88000000,
                (ink2() & 0x00FFFFFF) | 0x44000000}));
        s.setOnCheckedChangeListener((b, checked) -> onToggle.on(checked));
        return s;
    }

    /** A colour row: the value as #AARRGGBB, a bordered swatch of it; tap opens the picker. */
    private void colorRow(int titleRes, final String key) {
        int c = KakuUi.i(key);
        View swatch = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setStroke(Math.max(1, dp(1.5f)), ink());
        g.setCornerRadius(dp(4));
        swatch.setBackground(g);
        swatch.setTag(KakuViews.NO_SKIN);
        LinearLayout holder = new LinearLayout(this);
        holder.addView(swatch, new LinearLayout.LayoutParams(dp(38), dp(38)));
        holder.setTag(KakuViews.NO_SKIN);
        final String title = getString(titleRes);
        itemRow(IND_L2, title, String.format(Locale.ROOT, "#%08X", c), holder,
                v -> ColorPickerDialog.show(this, title, KakuUi.i(key), color -> {
                    if (color == KakuUi.i(key)) return;
                    KakuUi.set(key, color);
                    rebuild();
                }));
    }

    interface Label {
        String of(int value);
    }

    /** A slider row: title with the value on the right, the bar beneath; live while dragging. */
    private void slider(int titleRes, final String key, final int min, final int max, final int step,
                        final Label label, @Nullable final Runnable live) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(IND_L2), dp(4), dp(16), dp(4));
        row.setTag(KakuViews.NO_SKIN);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(body(getString(titleRes), 16, ink(), false, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = body(label.of(KakuUi.i(key)), 15, ink(), false, false);
        value.setGravity(Gravity.END);
        top.addView(value, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(top);

        SeekBar bar = new SeekBar(this);
        bar.setTag(KakuViews.NO_SKIN);
        bar.setMax((max - min) / step);
        bar.setProgress((KakuUi.i(key) - min) / step);
        ColorPickerDialog.tint(bar, accent());
        bar.setPadding(dp(8), dp(2), dp(8), dp(2));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = min + progress * step;
                KakuUi.set(key, v);
                value.setText(label.of(v));
                if (live != null) live.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                rebuild(); // the page itself repaints in the new value
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(row);
    }

    /** The font row: the chosen font's name, set in that very font. */
    private void fontRow(final String familyKey, final String weightKey) {
        String id = KakuUi.s(familyKey);
        TextView valueView = body(KakuFonts.displayName(this, id), 17, ink(), false, false);
        valueView.setTypeface(KakuFonts.at(this, id, KakuUi.i(weightKey), false));
        itemRow(IND_L2, getString(R.string.kaku_ui_font_family), null, valueView,
                v -> FontPickerDialog.show(this, getString(R.string.kaku_ui_font_family), KakuUi.s(familyKey),
                        KakuUi.i(weightKey), chosen -> {
                            KakuUi.set(familyKey, chosen);
                            rebuild();
                        }, () -> importFont(familyKey)));
    }

    /** A preview block, indented like the rows it previews. */
    private void preview(View content) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(IND_L2), dp(4), dp(16), dp(6));
        wrap.setTag(KakuViews.NO_SKIN);
        wrap.addView(body(getString(R.string.kaku_ui_preview), 12, ink2(), false, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        wrap.addView(content, lp);
        page.addView(wrap);
    }

    // ---- previews ---------------------------------------------------------------------------------

    /** The start screen in miniature: logo, title, a link and the progress line. */
    private View screenPreview() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg());
        g.setStroke(Math.max(1, dp(1)), ink2());
        box.setBackground(g);
        box.setTag(KakuViews.NO_SKIN);
        TextView logo = body("画", 44, ink(), false, false);
        box.addView(logo);
        box.addView(body(getString(R.string.kaku_ui_pv_secondary), 15, ink(), false, false));
        TextView link = body(getString(R.string.kaku_ui_pv_link), 13, accent(), false, false);
        link.setPadding(0, dp(4), 0, dp(4));
        box.addView(link);
        TextView second = body(getString(R.string.kaku_running), 12, ink2(), false, false);
        box.addView(second);
        View bar = new View(this);
        bar.setBackgroundColor(accent());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(3));
        lp.topMargin = dp(4);
        box.addView(bar, lp);
        return box;
    }

    /** The result window: its panel, the recognised characters and a dictionary entry. */
    private View windowPreview() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(KakuViews.NO_SKIN);
        refreshWindow(box);
        return box;
    }

    private void refreshWindow(View v) {
        if (!(v instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) v;
        box.removeAllViews();
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(KakuSkin.windowPanel(this));
        box.setAlpha(KakuSkin.windowAlpha());
        box.addView(charRow());
        // The result window's own renderer, one view per preview kept across refreshes.
        DictWebView web = (DictWebView) box.getTag(R.id.kaku_preview_web);
        if (web == null) {
            web = new DictWebView(this);
            web.setTag(KakuViews.NO_SKIN);
            box.setTag(R.id.kaku_preview_web, web);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        box.addView(web, lp);
        web.showPage(shiroikuma.kaku.dict.YomitanHtml.INSTANCE.page(this, sampleEntries(),
                java.util.Collections.emptyList(), null));
    }

    /** The previews' dictionary entry: 食べる, as the result window shows a real one. */
    private java.util.List<shiroikuma.kaku.dict.DictLookup.Entry> sampleEntries() {
        String dict = getString(R.string.kaku_ui_pv_dict);
        java.util.List<shiroikuma.kaku.dict.DictLookup.Definition> defs = new java.util.ArrayList<>();
        defs.add(new shiroikuma.kaku.dict.DictLookup.Definition(dict, 0, "v1 vt", "★", "v1", 0,
                "[\"to eat\"]", 1L));
        defs.add(new shiroikuma.kaku.dict.DictLookup.Definition(dict, 0, "v1 vt", "★", "v1", 0,
                "[\"to live on (e.g. a salary)\",\"to live off\",\"to subsist on\"]", 2L));
        java.util.List<shiroikuma.kaku.dict.DictLookup.Frequency> freqs = new java.util.ArrayList<>();
        freqs.add(new shiroikuma.kaku.dict.DictLookup.Frequency("JPDB", 184L, "184"));
        java.util.List<shiroikuma.kaku.dict.DictLookup.Pitch> pitches = new java.util.ArrayList<>();
        pitches.add(new shiroikuma.kaku.dict.DictLookup.Pitch("NHK", "たべる", java.util.Collections.singletonList("2")));
        java.util.List<shiroikuma.kaku.dict.DictLookup.Entry> out = new java.util.ArrayList<>();
        out.add(new shiroikuma.kaku.dict.DictLookup.Entry("食べる", "たべる", "食べて",
                java.util.Collections.singletonList("-て"), defs, freqs, pitches));
        return out;
    }

    /** A row of recognised characters, the looked-up word highlighted, one cell showing a swipe icon. */
    private View charRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(KakuViews.NO_SKIN);
        String chars = getString(R.string.kaku_ui_pv_chars);
        int cell = KakuSkin.charCellPx(this);
        for (int i = 0; i < chars.length(); i++) {
            TextView c = new TextView(this);
            c.setTag(KakuViews.NO_SKIN);
            c.setText(String.valueOf(chars.charAt(i)));
            c.setGravity(Gravity.CENTER);
            c.setTextColor(KakuUi.i(KakuUi.C_CHAR_TEXT));
            c.setTypeface(KakuSkin.charTypeface(this));
            c.setTextSize(TypedValue.COMPLEX_UNIT_DIP, KakuUi.i(KakuUi.CHAR_FONT_SIZE));
            c.setIncludeFontPadding(false);
            if (i >= chars.length() - 3) c.setBackground(KakuSkin.charHighlight(this));
            else if (i == 0) c.setBackground(KakuSkin.charTouched(this));
            row.addView(c, new LinearLayout.LayoutParams(cell, cell));
        }
        ImageView icon = new ImageView(this);
        icon.setTag(KakuViews.NO_SKIN);
        icon.setImageResource(R.drawable.icon_swap);
        icon.setColorFilter(KakuUi.i(KakuUi.C_ICON));
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        row.addView(icon, new LinearLayout.LayoutParams(cell, cell));
        return row;
    }

    private View charsPreview() {
        LinearLayout box = new LinearLayout(this);
        box.setTag(KakuViews.NO_SKIN);
        refreshChars(box);
        return box;
    }

    private void refreshChars(View v) {
        if (!(v instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) v;
        box.removeAllViews();
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setBackground(KakuSkin.windowPanel(this));
        box.addView(charRow());
    }

    /** The kanji choice window: the character's own image, then candidates, one under the finger. */
    private View choicePreview() {
        LinearLayout row = new LinearLayout(this);
        row.setTag(KakuViews.NO_SKIN);
        int cell = dp(44);
        View image = new View(this);
        image.setBackground(KakuSkin.choiceImage(this));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(cell, cell);
        ilp.rightMargin = dp(6);
        row.addView(image, ilp);
        String cands = getString(R.string.kaku_ui_pv_choices);
        for (int i = 0; i < cands.length(); i++) {
            TextView c = new TextView(this);
            c.setTag(KakuViews.NO_SKIN);
            char ch = cands.charAt(i);
            c.setText(String.valueOf(ch));
            c.setGravity(Gravity.CENTER);
            c.setTextSize(TypedValue.COMPLEX_UNIT_PX, cell / 1.5f);
            c.setTypeface(KakuSkin.charTypeface(this));
            c.setTextColor(KakuSkin.choiceTextColor(ch));
            c.setBackground(KakuSkin.choiceCell(this, i == 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
            lp.rightMargin = dp(6);
            row.addView(c, lp);
        }
        return row;
    }

    /** The capture box, ready (with its red line) and while reading. */
    private View capturePreview() {
        LinearLayout row = new LinearLayout(this);
        row.setTag(KakuViews.NO_SKIN);
        View ready = new View(this);
        ready.setBackground(KakuSkin.captureFrame(this, true, false));
        row.addView(ready, new LinearLayout.LayoutParams(dp(110), dp(64)));
        View busy = new View(this);
        busy.setBackground(KakuSkin.captureFrame(this, false, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), dp(64));
        lp.leftMargin = dp(12);
        row.addView(busy, lp);
        return row;
    }

    /** The handwriting editor's input line. */
    private View editPreview() {
        TextView tv = body(getString(R.string.kaku_ui_pv_edit), 18, KakuUi.i(KakuUi.C_EDIT_TEXT), false, false);
        tv.setTypeface(KakuSkin.charTypeface(this));
        tv.setBackgroundColor(KakuUi.i(KakuUi.C_EDIT_BG));
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        return tv;
    }

    private View dialogPreview() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setTag(KakuViews.NO_SKIN);
        refreshDialog(box);
        return box;
    }

    private void refreshDialog(View v) {
        if (!(v instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) v;
        box.removeAllViews();
        box.setBackground(KakuViews.panelBackground(this));
        box.addView(body(getString(R.string.kaku_ui_pv_dialog_title), 17, KakuUi.i(KakuUi.C_DLG_TEXT), false, true));
        box.addView(body(getString(R.string.kaku_ui_pv_dialog_body), 14, KakuUi.i(KakuUi.C_DLG_TEXT), false, false));
        LinearLayout buttons = KakuViews.buttonRow(this);
        buttons.setPadding(0, dp(8), 0, 0);
        buttons.addView(KakuViews.pill(this, getString(R.string.kaku_eim_ok), null));
        box.addView(buttons);
    }

    // ---------------------------------------------------------------------------------------------
    // pickers (SAF) — the Export / Import panel's host side, and font import
    // ---------------------------------------------------------------------------------------------

    @Override
    public void pickExportDir(@Nullable Uri initial) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (initial != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        startActivityForResult(i, REQ_DIR);
    }

    @Override
    public void pickImportFile(@Nullable Uri initial) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "*/*"});
        if (initial != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    public void onChainFinished() {
        finish();
    }

    private void importFont(@Nullable String familyKey) {
        pendingFontKey = familyKey;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_FONT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_DIR) {
            panel.onDirPicked(uri);
            rebuild();
        } else if (requestCode == REQ_IMPORT) {
            panel.onImportFilePicked(uri);
        } else if (requestCode == REQ_OCR_ENCODER || requestCode == REQ_OCR_DECODER || requestCode == REQ_OCR_VOCAB) {
            if (uri != null) {
                String target = requestCode == REQ_OCR_ENCODER ? MangaOcr.ENCODER
                        : requestCode == REQ_OCR_DECODER ? MangaOcr.DECODER : MangaOcr.VOCAB;
                importOcrFiles(java.util.Collections.singletonList(uri), target);
            }
        } else if (requestCode == REQ_OCR_ALL && resultCode == RESULT_OK && data != null) {
            java.util.List<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) importOcrFiles(uris, null);
        } else if (requestCode == REQ_TESS && uri != null) {
            importTesseract(uri);
        } else if (requestCode == REQ_DICT && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri u : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // the session grant still reaches the service through the intent's ClipData
                }
            }
            if (!uris.isEmpty()) {
                shiroikuma.kaku.dict.DictImportService.Companion.start(this, uris);
                importDialogHidden = false;
                rebuild();
                showImportDialog();
            }
        } else if (requestCode == REQ_FONT && uri != null) {
            try {
                String id = KakuFonts.importFont(this, uri);
                if (pendingFontKey != null) KakuUi.set(pendingFontKey, id);
                KakuViews.toast(this, getString(R.string.kaku_font_imported, KakuFonts.displayName(this, id)));
                rebuild();
            } catch (Exception e) {
                KakuViews.showInfo(this, getString(R.string.kaku_font_import_fail_title),
                        String.valueOf(e.getMessage()), true, null);
            }
        }
    }
}
