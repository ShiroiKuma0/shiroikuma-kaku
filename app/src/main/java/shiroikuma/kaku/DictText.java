package shiroikuma.kaku;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;

import androidx.annotation.NonNull;

import java.util.List;

import ca.fuwafuwa.kaku.Constants;
import ca.fuwafuwa.kaku.Database.JmDictDatabase.Models.EntryOptimized;
import ca.fuwafuwa.kaku.LangUtils;
import ca.fuwafuwa.kaku.Search.JmSearchResult;

/** Dictionary results as styled text, shared by the result window and the instant popup. */
public final class DictText
{
    private DictText()
    {
    }

    /**
     * The entries as one styled text: headword (larger, bold, headword colour), reading (reading
     * colour), deinflection reason, then the numbered senses with their parts of speech in the
     * part-of-speech colour — all from the 白い熊 画 UI page.
     */
    @NonNull
    public static CharSequence build(@NonNull Context context, @NonNull List<JmSearchResult> jmResults)
    {
        return build(context, jmResults, 0);
    }

    /**
     * The results as the result window's HTML page ({@link shiroikuma.kaku.dict.YomitanHtml}); the
     * notice (no dictionary imported) is a page of its own.
     */
    @NonNull
    public static String page(@NonNull Context context, @NonNull List<JmSearchResult> jmResults)
    {
        String notice = !jmResults.isEmpty() ? jmResults.get(0).getNotice() : null;
        java.util.List<shiroikuma.kaku.dict.DictLookup.Entry> entries = new java.util.ArrayList<>();
        for (JmSearchResult r : jmResults) if (r.getYomitan() != null) entries.add(r.getYomitan());
        List<shiroikuma.kaku.dict.DictLookup.Kanji> kanji = jmResults.isEmpty()
                ? java.util.Collections.emptyList() : jmResults.get(0).getKanji();
        return shiroikuma.kaku.dict.YomitanHtml.INSTANCE.page(context, entries, kanji, notice);
    }

    /** As {@link #build(Context, List)}, each entry cut to {@code maxSenses} senses (0 = all). */
    @NonNull
    public static CharSequence build(@NonNull Context context, @NonNull List<JmSearchResult> jmResults, int maxSenses)
    {
        if (!jmResults.isEmpty() && jmResults.get(0).getNotice() != null) {
            SpannableStringBuilder n = new SpannableStringBuilder(jmResults.get(0).getNotice());
            n.setSpan(new ForegroundColorSpan(KakuUi.WARN), 0, n.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return n;
        }
        if (!jmResults.isEmpty() && (jmResults.get(0).getYomitan() != null || !jmResults.get(0).getKanji().isEmpty())) {
            java.util.List<shiroikuma.kaku.dict.DictLookup.Entry> entries = new java.util.ArrayList<>();
            for (JmSearchResult r : jmResults) if (r.getYomitan() != null) entries.add(r.getYomitan());
            return shiroikuma.kaku.dict.YomitanText.INSTANCE.build(context, entries, jmResults.get(0).getKanji(), maxSenses);
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int headPx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                KakuUi.i(KakuUi.DICT_FONT_SIZE) * KakuUi.i(KakuUi.DICT_HEAD_SCALE) / 100f,
                context.getResources().getDisplayMetrics()));

        for (JmSearchResult jmSearchResult : jmResults)
        {
            EntryOptimized entry = jmSearchResult.getEntry();
            boolean jmdict = Constants.DB_JMDICT_NAME.equals(entry.getDictionary());

            int start = sb.length();
            sb.append(entry.getKanji());
            sb.setSpan(new ForegroundColorSpan(KakuUi.i(KakuUi.C_DICT_HEADWORD)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(headPx), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new KakuTypefaceSpan(KakuSkin.dictHeadTypeface(context)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            if (!entry.getReadings().isEmpty()){
                start = sb.length();
                sb.append(jmdict ? " (" : " ").append(entry.getReadings()).append(jmdict ? ")" : "");
                sb.setSpan(new ForegroundColorSpan(KakuUi.i(KakuUi.C_DICT_READING)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            String deinfReason = jmSearchResult.getDeinfInfo().getReason();
            if (deinfReason != null && !deinfReason.isEmpty()){
                start = sb.length();
                sb.append(String.format(" %s", deinfReason));
                sb.setSpan(new ForegroundColorSpan(KakuUi.i(KakuUi.C_DICT_POS)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            sb.append("\n");
            appendMeaning(sb, entry, maxSenses);
            sb.append("\n\n");
        }

        if (sb.length() > 2)
        {
            sb.delete(sb.length() - 2, sb.length());
        }

        return sb;
    }

    private static void appendMeaning(SpannableStringBuilder sb, EntryOptimized entry, int maxSenses)
    {
        String[] meanings = entry.getMeanings().split("\ufffc", -1);
        String[] pos = entry.getPos().split("\ufffc", -1);

        for (int i = 0; i < meanings.length; i++){
            if (maxSenses > 0 && i >= maxSenses){
                sb.append(" [......]");
                break;
            }
            if (i != 0){
                sb.append(" ");
            }
            sb.append(LangUtils.Companion.ConvertIntToCircledNum(i + 1));
            sb.append(" ");
            if (Constants.DB_JMDICT_NAME.equals(entry.getDictionary()) && i < pos.length && !pos[i].isEmpty()){
                int start = sb.length();
                sb.append(String.format("(%s) ", pos[i]));
                sb.setSpan(new ForegroundColorSpan(KakuUi.i(KakuUi.C_DICT_POS)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append(meanings[i]);
        }
    }
}
