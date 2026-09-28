package shiroikuma.kaku;

import android.app.Activity;
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
import shiroikuma.kaku.backup.ShiroikumaExport;

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
        dictPv[0] = windowPreview();
        preview(dictPv[0]);

        sub(getString(R.string.kaku_ui_sub_chars));
        final View[] charPv = new View[1];
        fontRow(KakuUi.CHAR_FONT_FAMILY, KakuUi.CHAR_FONT_WEIGHT);
        slider(R.string.kaku_ui_font_weight, KakuUi.CHAR_FONT_WEIGHT, 100, 900, 100, String::valueOf,
                () -> refreshChars(charPv[0]));
        slider(R.string.kaku_ui_font_size, KakuUi.CHAR_FONT_SIZE, 12, 32, 1, v -> v + " dp",
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

    @SuppressWarnings("unused")
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
        TextView dict = new TextView(this);
        dict.setTag(KakuViews.NO_SKIN);
        dict.setTypeface(KakuSkin.dictTypeface(this));
        dict.setTextSize(TypedValue.COMPLEX_UNIT_SP, KakuUi.i(KakuUi.DICT_FONT_SIZE));
        dict.setTextColor(KakuUi.i(KakuUi.C_DICT_TEXT));
        dict.setText(sampleEntry());
        dict.setPadding(0, dp(6), 0, 0);
        box.addView(dict);
    }

    /** The dictionary entry of the previews, styled as DictText styles real results. */
    private CharSequence sampleEntry() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int headPx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                KakuUi.i(KakuUi.DICT_FONT_SIZE) * KakuUi.i(KakuUi.DICT_HEAD_SCALE) / 100f,
                getResources().getDisplayMetrics()));
        int s = sb.length();
        sb.append(getString(R.string.kaku_ui_pv_headword));
        sb.setSpan(new ForegroundColorSpan(KakuUi.i(KakuUi.C_DICT_HEADWORD)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(headPx), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new KakuTypefaceSpan(KakuSkin.dictHeadTypeface(this)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s = sb.length();
        sb.append(getString(R.string.kaku_ui_pv_reading));
        sb.setSpan(new ForegroundColorSpan(KakuUi.i(KakuUi.C_DICT_READING)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("\n① ");
        s = sb.length();
        sb.append(getString(R.string.kaku_ui_pv_pos));
        sb.setSpan(new ForegroundColorSpan(KakuUi.i(KakuUi.C_DICT_POS)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(getString(R.string.kaku_ui_pv_meaning));
        return sb;
    }

    /** A row of recognised characters, the looked-up word highlighted, one cell showing a swipe icon. */
    private View charRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(KakuViews.NO_SKIN);
        String chars = getString(R.string.kaku_ui_pv_chars);
        int cell = dp(37);
        for (int i = 0; i < chars.length(); i++) {
            TextView c = new TextView(this);
            c.setTag(KakuViews.NO_SKIN);
            c.setText(String.valueOf(chars.charAt(i)));
            c.setGravity(Gravity.CENTER);
            c.setTextColor(KakuUi.i(KakuUi.C_CHAR_TEXT));
            c.setTypeface(KakuSkin.charTypeface(this));
            c.setTextSize(TypedValue.COMPLEX_UNIT_DIP, KakuUi.i(KakuUi.CHAR_FONT_SIZE));
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
