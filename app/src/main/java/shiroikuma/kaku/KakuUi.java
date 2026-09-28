package shiroikuma.kaku;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 白い熊 画 UI — every setting of the fork's UI page, with its default. One SharedPreferences file
 * ({@link #PREFS}) holds them all; it is exactly what the Export / Import "UI" category carries.
 *
 * <p>The defaults are the house black-yellow look: pure {@code #000000} grounds, pure
 * {@code #FFFF00} ink and borders.
 *
 * <p>Readers call the static getters; the page writes through {@link #set}, which bumps
 * {@link #stamp()} so every window painted with an older stamp repaints the next time it shows.
 */
public final class KakuUi {

    public static final String PREFS = "kaku_ui";

    public static final int BLACK = 0xFF000000;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int YELLOW_DIM = 0xFFC8C800;
    public static final int YELLOW_GLASS = 0x55FFFF00;
    public static final int WARN = 0xFFFF5252;

    // ---- keys ------------------------------------------------------------------------------------

    // App screens (start screen, tutorial, this page)
    public static final String C_BG = "c_bg";
    public static final String C_TEXT = "c_text";
    public static final String C_TEXT2 = "c_text2";
    public static final String C_ACCENT = "c_accent";

    // Lookup windows (the result window, the instant result popups, the kanji choice, the editor)
    public static final String C_WIN_BG = "c_win_bg";
    public static final String C_WIN_BORDER = "c_win_border";
    public static final String WIN_BORDER_W = "win_border_w";   // tenths of a dp
    public static final String WIN_RADIUS = "win_radius";       // dp
    public static final String WIN_ALPHA = "win_alpha";         // percent opacity of every overlay

    // Dictionary text
    public static final String C_DICT_HEADWORD = "c_dict_headword";
    public static final String C_DICT_READING = "c_dict_reading";
    public static final String C_DICT_POS = "c_dict_pos";
    public static final String C_DICT_TEXT = "c_dict_text";
    public static final String DICT_FONT_FAMILY = "dict_font_family";
    public static final String DICT_FONT_WEIGHT = "dict_font_weight";
    public static final String DICT_FONT_SIZE = "dict_font_size"; // sp
    public static final String DICT_HEAD_SCALE = "dict_head_scale"; // percent of the text size
    public static final String DICT_ZOOM = "dict_zoom"; // percent — the result window's pinch zoom

    // Recognised characters (the grid of OCR'd characters)
    public static final String C_CHAR_TEXT = "c_char_text";
    public static final String C_CHAR_HL_FILL = "c_char_hl_fill";
    public static final String C_CHAR_HL_BORDER = "c_char_hl_border";
    public static final String CHAR_HL_BORDER_W = "char_hl_border_w"; // tenths of a dp
    public static final String CHAR_FONT_FAMILY = "char_font_family";
    public static final String CHAR_FONT_WEIGHT = "char_font_weight";
    public static final String CHAR_FONT_SIZE = "char_font_size"; // dp
    public static final String C_ICON = "c_icon";               // the swap / edit / delete swipe icons

    // Kanji choice window
    public static final String C_CHOICE_BG = "c_choice_bg";
    public static final String C_CHOICE_BORDER = "c_choice_border";
    public static final String C_CHOICE_ACTIVE = "c_choice_active";
    public static final String C_CHOICE_KANJI = "c_choice_kanji";
    public static final String C_CHOICE_HIRAGANA = "c_choice_hiragana";
    public static final String C_CHOICE_KATAKANA = "c_choice_katakana";
    public static final String C_CHOICE_OTHER = "c_choice_other";

    // Capture box (its 1 px red "ready" line is read back from the screenshot and stays red)
    public static final String C_CAPTURE_BORDER = "c_capture_border";
    public static final String C_CAPTURE_BUSY = "c_capture_busy";

    // Edit window
    public static final String C_EDIT_BG = "c_edit_bg";
    public static final String C_EDIT_TEXT = "c_edit_text";

    // Dialogs
    public static final String C_DLG_BG = "c_dlg_bg";
    public static final String C_DLG_TEXT = "c_dlg_text";
    public static final String C_DLG_BORDER = "c_dlg_border";
    public static final String DLG_BORDER_W = "dlg_border_w";   // tenths of a dp
    public static final String DLG_RADIUS = "dlg_radius";       // dp

    // Interface text and this page's headings
    public static final String FONT_FAMILY = "font_family";     // "" = system, else a KakuFonts id
    public static final String FONT_WEIGHT = "font_weight";     // 100..900
    public static final String FONT_SCALE = "font_scale";       // percent
    public static final String HEAD_FONT_FAMILY = "head_font_family";
    public static final String HEAD_FONT_WEIGHT = "head_font_weight";
    public static final String HEAD_FONT_SIZE = "head_font_size"; // sp

    /** The OCR engine: {@link #ENGINE_MANGAOCR} (falls back to Tesseract until its model is imported) or {@link #ENGINE_TESSERACT}. */
    public static final String OCR_ENGINE = "ocr_engine";
    public static final String ENGINE_MANGAOCR = "mangaocr";
    public static final String ENGINE_TESSERACT = "tesseract";

    /** The camera view: live mode, how long the picture must be still before it is read (ms), its launcher icon. */
    public static final String CAM_LIVE = "cam_live";
    public static final String CAM_LIVE_SETTLE = "cam_live_settle";
    /**
     * Instant mode reads a released box whose narrower side is at most this many dp;
     * {@link #INSTANT_ANY} and above = any size (upstream Kaku's fixed limit was about 45 dp).
     */
    public static final String INSTANT_MAX_DP = "instant_max_dp";
    public static final int INSTANT_ANY = 300;
    public static final String CAM_LAUNCHER = "cam_launcher";

    /** Colour-picker memory: the last colours chosen, newest first, comma-separated ARGB ints. */
    public static final String RECENT_COLORS = "recent_colors";
    public static final int MAX_RECENT = 8;

    private static final List<Setting> ALL = new ArrayList<>();

    /** A setting's key, kind and default — the page, reset and export all read this one table. */
    public static final class Setting {
        public final String key;
        public final boolean isBool;
        public final boolean isString;
        public final int defInt;
        public final boolean defBool;
        public final String defString;

        Setting(String key, int def) {
            this.key = key;
            this.isBool = false;
            this.isString = false;
            this.defInt = def;
            this.defBool = false;
            this.defString = null;
        }

        Setting(String key, boolean def) {
            this.key = key;
            this.isBool = true;
            this.isString = false;
            this.defInt = 0;
            this.defBool = def;
            this.defString = null;
        }

        Setting(String key, String def) {
            this.key = key;
            this.isBool = false;
            this.isString = true;
            this.defInt = 0;
            this.defBool = false;
            this.defString = def;
        }
    }

    static {
        ALL.add(new Setting(C_BG, BLACK));
        ALL.add(new Setting(C_TEXT, YELLOW));
        ALL.add(new Setting(C_TEXT2, YELLOW_DIM));
        ALL.add(new Setting(C_ACCENT, YELLOW));

        ALL.add(new Setting(C_WIN_BG, BLACK));
        ALL.add(new Setting(C_WIN_BORDER, YELLOW));
        ALL.add(new Setting(WIN_BORDER_W, 15));
        ALL.add(new Setting(WIN_RADIUS, 8));
        ALL.add(new Setting(WIN_ALPHA, 90));

        ALL.add(new Setting(C_DICT_HEADWORD, YELLOW));
        ALL.add(new Setting(C_DICT_READING, YELLOW));
        ALL.add(new Setting(C_DICT_POS, YELLOW_DIM));
        ALL.add(new Setting(C_DICT_TEXT, YELLOW));
        ALL.add(new Setting(DICT_FONT_FAMILY, ""));
        ALL.add(new Setting(DICT_FONT_WEIGHT, 400));
        ALL.add(new Setting(DICT_FONT_SIZE, 15));
        ALL.add(new Setting(DICT_HEAD_SCALE, 120));
        ALL.add(new Setting(DICT_ZOOM, 100));

        ALL.add(new Setting(C_CHAR_TEXT, YELLOW));
        ALL.add(new Setting(C_CHAR_HL_FILL, YELLOW_GLASS));
        ALL.add(new Setting(C_CHAR_HL_BORDER, YELLOW));
        ALL.add(new Setting(CHAR_HL_BORDER_W, 10));
        ALL.add(new Setting(CHAR_FONT_FAMILY, ""));
        ALL.add(new Setting(CHAR_FONT_WEIGHT, 400));
        ALL.add(new Setting(CHAR_FONT_SIZE, 40));
        ALL.add(new Setting(C_ICON, YELLOW));

        ALL.add(new Setting(C_CHOICE_BG, BLACK));
        ALL.add(new Setting(C_CHOICE_BORDER, YELLOW));
        ALL.add(new Setting(C_CHOICE_ACTIVE, 0x88FFFF00));
        ALL.add(new Setting(C_CHOICE_KANJI, YELLOW));
        ALL.add(new Setting(C_CHOICE_HIRAGANA, YELLOW));
        ALL.add(new Setting(C_CHOICE_KATAKANA, YELLOW));
        ALL.add(new Setting(C_CHOICE_OTHER, YELLOW_DIM));

        ALL.add(new Setting(C_CAPTURE_BORDER, YELLOW));
        ALL.add(new Setting(C_CAPTURE_BUSY, 0x33FFFF00));

        ALL.add(new Setting(C_EDIT_BG, BLACK));
        ALL.add(new Setting(C_EDIT_TEXT, YELLOW));

        ALL.add(new Setting(C_DLG_BG, BLACK));
        ALL.add(new Setting(C_DLG_TEXT, YELLOW));
        ALL.add(new Setting(C_DLG_BORDER, YELLOW));
        ALL.add(new Setting(DLG_BORDER_W, 20));
        ALL.add(new Setting(DLG_RADIUS, 8));

        ALL.add(new Setting(OCR_ENGINE, ENGINE_MANGAOCR));

        ALL.add(new Setting(CAM_LIVE, false));
        ALL.add(new Setting(CAM_LIVE_SETTLE, 600));
        ALL.add(new Setting(INSTANT_MAX_DP, INSTANT_ANY));
        ALL.add(new Setting(CAM_LAUNCHER, true));

        ALL.add(new Setting(FONT_FAMILY, ""));
        ALL.add(new Setting(FONT_WEIGHT, 400));
        ALL.add(new Setting(FONT_SCALE, 100));
        ALL.add(new Setting(HEAD_FONT_FAMILY, ""));
        ALL.add(new Setting(HEAD_FONT_WEIGHT, 700));
        ALL.add(new Setting(HEAD_FONT_SIZE, 20));
    }

    private static SharedPreferences sp;
    private static volatile int stamp = 1;

    private KakuUi() {
    }

    public static void init(@NonNull Context context) {
        if (sp == null) {
            sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences sp() {
        return sp;
    }

    @NonNull
    public static List<Setting> settings() {
        return ALL;
    }

    private static Setting find(String key) {
        for (Setting s : ALL) {
            if (s.key.equals(key)) return s;
        }
        throw new IllegalArgumentException("unknown setting " + key);
    }

    // ---- read ------------------------------------------------------------------------------------

    public static int i(String key) {
        Setting s = find(key);
        return sp() == null ? s.defInt : sp().getInt(key, s.defInt);
    }

    public static boolean b(String key) {
        Setting s = find(key);
        return sp() == null ? s.defBool : sp().getBoolean(key, s.defBool);
    }

    @NonNull
    public static String s(String key) {
        Setting s = find(key);
        String v = sp() == null ? s.defString : sp().getString(key, s.defString);
        return v == null ? "" : v;
    }

    /** Monotonic change counter: a window repaints when the stamp it painted with is stale. */
    public static int stamp() {
        return stamp;
    }

    // ---- write -----------------------------------------------------------------------------------

    public static void set(String key, int value) {
        sp().edit().putInt(key, value).apply();
        changed();
    }

    /**
     * Store without marking the look changed — for a value the showing view already applied itself
     * (the result window's pinch zoom), so no window repaints for it.
     */
    public static void setQuietly(String key, int value) {
        sp().edit().putInt(key, value).apply();
    }

    public static void set(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        changed();
    }

    public static void set(String key, String value) {
        sp().edit().putString(key, value).apply();
        changed();
    }

    /** Back to the house defaults — every setting, the colour memory kept. */
    public static void reset() {
        SharedPreferences.Editor e = sp().edit();
        for (Setting s : ALL) e.remove(s.key);
        e.commit();
        changed();
    }

    public static void changed() {
        stamp++;
    }

    // ---- colour memory ---------------------------------------------------------------------------

    /** The one-click swatches: remembered colours newest first, topped up with the house colours. */
    @NonNull
    public static List<Integer> recentColors() {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        String raw = sp() == null ? "" : sp().getString(RECENT_COLORS, "");
        if (raw != null) {
            for (String part : raw.split(",")) {
                try {
                    if (!part.trim().isEmpty()) out.add((int) Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // a damaged entry just drops out
                }
            }
        }
        out.add(BLACK);
        out.add(YELLOW);
        out.add(Color.WHITE);
        out.add(YELLOW_DIM);
        List<Integer> list = new ArrayList<>(out);
        return list.size() > MAX_RECENT ? list.subList(0, MAX_RECENT) : list;
    }

    public static void rememberColor(int color) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        out.add(color);
        String raw = sp().getString(RECENT_COLORS, "");
        if (raw != null) {
            for (String part : raw.split(",")) {
                try {
                    if (!part.trim().isEmpty()) out.add((int) Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Integer c : out) {
            if (n++ >= MAX_RECENT) break;
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        sp().edit().putString(RECENT_COLORS, sb.toString()).apply();
    }

    // ---- derived ---------------------------------------------------------------------------------

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** A border width setting (tenths of a dp) in pixels; 0 stays 0. */
    public static int borderPx(Context c, String key) {
        int tenths = i(key);
        if (tenths <= 0) return 0;
        return Math.max(1, Math.round(dp(c, tenths / 10f)));
    }
}
