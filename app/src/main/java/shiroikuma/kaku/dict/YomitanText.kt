package shiroikuma.kaku.dict

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import shiroikuma.kaku.KakuSkin
import shiroikuma.kaku.KakuTypefaceSpan
import shiroikuma.kaku.KakuUi

/**
 * Yomitan results as styled text for the result windows, in the 白い熊 画 UI colours: per word the
 * headword (larger, bold), its reading, how it was deinflected, frequency and pitch-accent badges;
 * then every definition numbered ①②…, its dictionary named when more than one dictionary answers,
 * part-of-speech / definition tags dimmed; then the looked-up character's kanji information.
 *
 * Structured content (Jitendex, JMdict for Yomitan) is flattened to text: lists become bullet
 * lines, ruby shows its reading in parentheses, tables become lines of cells, images are left out.
 */
object YomitanText
{
    fun build(context: Context, entries: List<DictLookup.Entry>, kanji: List<DictLookup.Kanji>, maxSenses: Int): CharSequence
    {
        val sb = SpannableStringBuilder()
        val headPx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                KakuUi.i(KakuUi.DICT_FONT_SIZE) * KakuUi.i(KakuUi.DICT_HEAD_SCALE) / 100f, context.resources.displayMetrics))
        val cHead = KakuUi.i(KakuUi.C_DICT_HEADWORD)
        val cReading = KakuUi.i(KakuUi.C_DICT_READING)
        val cPos = KakuUi.i(KakuUi.C_DICT_POS)

        for ((n, e) in entries.withIndex())
        {
            if (n > 0) sb.append("\n\n")
            var s = sb.length
            sb.append(e.expression)
            sb.setSpan(ForegroundColorSpan(cHead), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AbsoluteSizeSpan(headPx), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(KakuTypefaceSpan(KakuSkin.dictHeadTypeface(context)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (e.reading.isNotEmpty() && e.reading != e.expression)
            {
                s = sb.length
                sb.append(" (").append(e.reading).append(")")
                sb.setSpan(ForegroundColorSpan(cReading), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val badges = ArrayList<String>()
            if (e.deinflection.isNotEmpty()) badges.add("← " + e.deinflection.joinToString(" · "))
            for (p in e.pitches.take(2)) badges.add("[" + p.positions.joinToString(",") + "]")
            for (f in e.frequencies.take(2)) badges.add("${f.dictionary.take(12)} ${f.display}")
            if (badges.isNotEmpty())
            {
                s = sb.length
                sb.append("  ").append(badges.joinToString("  "))
                sb.setSpan(ForegroundColorSpan(cPos), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.85f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            val several = e.definitions.map { it.dictionary }.distinct().size > 1
            var lastDict = ""
            var senses = 0
            for (d in e.definitions)
            {
                if (maxSenses > 0 && senses >= maxSenses)
                {
                    sb.append(" [……]")
                    break
                }
                if (several && d.dictionary != lastDict)
                {
                    s = sb.length
                    sb.append("\n").append(d.dictionary)
                    sb.setSpan(ForegroundColorSpan(cPos), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.8f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    lastDict = d.dictionary
                }
                sb.append("\n").append(circled(++senses)).append(" ")
                val tags = (d.definitionTags + " " + d.termTags).trim().split(' ').filter { it.isNotEmpty() }.distinct()
                if (tags.isNotEmpty())
                {
                    s = sb.length
                    sb.append("(").append(tags.joinToString(", ")).append(") ")
                    sb.setSpan(ForegroundColorSpan(cPos), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                appendGlossary(sb, d.glossaryJson, cPos)
            }
        }

        for (k in kanji)
        {
            if (sb.isNotEmpty()) sb.append("\n\n")
            var s = sb.length
            sb.append(k.character)
            sb.setSpan(ForegroundColorSpan(cHead), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AbsoluteSizeSpan(Math.round(headPx * 1.3f)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s = sb.length
            sb.append("  ").append(k.dictionary)
            sb.setSpan(ForegroundColorSpan(cPos), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (k.onyomi.isNotEmpty())
            {
                sb.append("\n")
                s = sb.length
                sb.append("音 ").append(k.onyomi.replace(' ', '・'))
                sb.setSpan(ForegroundColorSpan(cReading), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (k.kunyomi.isNotEmpty())
            {
                sb.append("\n")
                s = sb.length
                sb.append("訓 ").append(k.kunyomi.replace(' ', '・'))
                sb.setSpan(ForegroundColorSpan(cReading), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (k.meanings.isNotEmpty()) sb.append("\n").append(k.meanings.joinToString("; "))
            val stats = k.stats.map { "${it.first} ${it.second}" } + k.frequencies.map { "${it.dictionary} ${it.display}" }
            if (stats.isNotEmpty())
            {
                s = sb.length
                sb.append("\n").append(stats.joinToString(" · "))
                sb.setSpan(ForegroundColorSpan(cPos), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.85f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun circled(n: Int): String = if (n in 1..20) (0x2460 + n - 1).toChar().toString() else "($n)"

    /** A definition's glossary array: plain strings, `text`, `structured-content`, deinflection pointers. */
    private fun appendGlossary(sb: SpannableStringBuilder, json: String, dim: Int)
    {
        val array = try { JsonParser.parseString(json) } catch (e: Exception) { null } ?: return
        val items: List<JsonElement> = if (array.isJsonArray) array.asJsonArray.toList() else listOf(array)
        var first = true
        for (item in items)
        {
            val start = sb.length
            if (!first) sb.append("; ")
            val before = sb.length
            when
            {
                item.isJsonPrimitive -> sb.append(item.asString)
                item.isJsonArray ->
                {
                    // [uninflected term, [inflection rules]] — this entry is a form of another word.
                    val a = item.asJsonArray
                    if (a.size() > 0 && a[0].isJsonPrimitive) sb.append("→ ").append(a[0].asString)
                }
                item.isJsonObject ->
                {
                    val o = item.asJsonObject
                    when (o.get("type")?.asString)
                    {
                        "text" -> sb.append(o.get("text")?.asString ?: "")
                        "structured-content" -> structured(sb, o.get("content"), dim)
                        else -> {}
                    }
                }
            }
            if (sb.length == before) sb.delete(start, sb.length) else first = false
        }
        trimTrailingNewlines(sb)
    }

    /** Structured content (Yomitan's HTML-like tree) flattened into [sb]. */
    private fun structured(sb: SpannableStringBuilder, node: JsonElement?, dim: Int)
    {
        if (node == null || node.isJsonNull) return
        when
        {
            node.isJsonPrimitive -> sb.append(node.asString)
            node.isJsonArray -> for (c in node.asJsonArray) structured(sb, c, dim)
            node.isJsonObject ->
            {
                val o = node.asJsonObject
                val tag = o.get("tag")?.asString ?: ""
                val content = o.get("content")
                val dataContent = (o.get("data") as? JsonObject)?.get("content")?.asString ?: ""
                val start = sb.length
                when (tag)
                {
                    "br" -> sb.append("\n")
                    "img", "rp" -> {}
                    "rt" -> { sb.append("("); structured(sb, content, dim); sb.append(")") }
                    "ul", "ol" ->
                    {
                        val items = if (content != null && content.isJsonArray) content.asJsonArray else JsonArray().apply { content?.let { add(it) } }
                        var n = 0
                        for (li in items)
                        {
                            newline(sb)
                            // A list's own marker (Jitendex numbers its senses ①②) wins over ours.
                            val marker = ((li as? JsonObject)?.get("style") as? JsonObject)?.get("listStyleType")?.asString
                                    ?.trim('"', '\'')?.takeIf { it.isNotEmpty() && it != "none" && !it.matches(Regex("[a-z-]+")) }
                            ++n
                            sb.append(if (marker != null) "$marker " else if (tag == "ol") "$n. " else "• ")
                            structured(sb, (li as? JsonObject)?.get("content") ?: li, dim)
                        }
                    }
                    "div", "p", "li", "details", "summary", "table", "tr" ->
                    {
                        newline(sb)
                        if (tag == "tr" && content != null && content.isJsonArray)
                        {
                            content.asJsonArray.forEachIndexed { i, cell ->
                                if (i > 0) sb.append(" | ")
                                structured(sb, cell, dim)
                            }
                        }
                        else structured(sb, content, dim)
                    }
                    else ->
                    {
                        structured(sb, content, dim)
                        // Tag chips (Jitendex's "1-dan", "transitive") must not run together.
                        if ((o.get("data") as? JsonObject)?.get("class")?.asString == "tag" && sb.length > start) sb.append(' ')
                    }
                }
                if (dataContent in DIM_CONTENT && sb.length > start)
                {
                    sb.setSpan(ForegroundColorSpan(dim), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.9f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (tag == "summary" && sb.length > start) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** The data-content roles shown dimmed (notes, sources, cross-references, example translations). */
    private val DIM_CONTENT = setOf("attribution", "xref", "xref-glossary", "extra-info", "notes", "info-gloss",
            "example-sentence-b", "forms", "antonym", "lang-source")

    private fun newline(sb: SpannableStringBuilder)
    {
        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n' && !sb.endsWith(" ")) sb.append("\n")
    }

    private fun trimTrailingNewlines(sb: SpannableStringBuilder)
    {
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
    }
}
