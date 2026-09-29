package shiroikuma.kaku.dict

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import shiroikuma.kaku.KakuFonts
import shiroikuma.kaku.KakuUi
import java.io.File
import java.util.Locale

/**
 * Yomitan results as an HTML page for the result window's [shiroikuma.kaku.DictWebView], rendered
 * the way Yomitan renders them: structured content becomes real elements (Yomitan's
 * `StructuredContentGenerator` — `data-sc-*` attributes, whitelisted inline styles, ruby, tables,
 * details), each dictionary's own `styles.css` applies inside `@scope` of that dictionary's
 * definitions, and a theme layer on top repaints everything in the 白い熊 画 UI colours — tag chips,
 * the example / note / cross-reference boxes and links included. Per word: the headword with its
 * furigana, chips for how it was deinflected, its term tags and frequencies, a pitch-accent graph
 * per pitch dictionary, then the definitions (numbered ①② when there are several), each
 * dictionary named. Then the looked-up character's kanji information.
 *
 * Images come from the dictionary's media folder ([DictDb.mediaDir]), sized and drawn by Yomitan's
 * rules (px or em, monochrome ones painted in the text colour, a background behind the others); an
 * image whose file is missing (a dictionary imported before images were) shows its alt text. Links
 * are shown but lead nowhere — the app has no network, and a click is swallowed by the view.
 */
object YomitanHtml
{
    /** The look of a page, from the 白い熊 画 UI settings (colours as ARGB). */
    data class Theme(val bg: Int, val text: Int, val head: Int, val reading: Int, val pos: Int, val warn: Int,
                     val sizePx: Float, val headScale: Float, val weight: Int,
                     /** A CSS font-family value; `"kaku-dict"` refers to [fontFileUrl]. */
                     val family: String, val fontFileUrl: String?)

    /** How a page is laid out: the result window's full page, or the instant popup's compact one. */
    data class Options(
            /** The label of a "← back" chip at the top (a cross-reference was followed); null = none. */
            val back: String? = null,
            /** The popup: examples, cross-references, notes and kanji tables left out, [maxSenses] kept. */
            val compact: Boolean = false,
            /** Definitions (and structured senses) per word; 0 = all. */
            val maxSenses: Int = 0)

    /** The href of the back chip; [shiroikuma.kaku.DictWebView] hands it to its link handler. */
    const val BACK_URL = "kaku:back"

    @JvmOverloads
    fun page(context: Context, entries: List<DictLookup.Entry>, kanji: List<DictLookup.Kanji>, notice: String?,
             options: Options = Options()): String
    {
        val dicts = try { DictDb.get(context).dictionaries() } catch (e: Exception) { emptyList() }
        val styles = LinkedHashMap<String, String>()
        for (d in dicts) d.styles?.let { styles[d.title] = it }
        val dirs = dicts.associate { it.title to DictDb.mediaDir(context, it.id) }
        return compose(theme(context), entries, kanji, notice, styles, options) { title, path -> mediaUrl(dirs[title], path) }
    }

    /** The file URL of a dictionary image, or null when it is not there (or would leave its folder). */
    fun mediaUrl(dir: File?, path: String): String?
    {
        if (dir == null || path.isEmpty()) return null
        return try
        {
            val f = File(dir, path)
            if (f.isFile && f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f.toURI().toString() else null
        }
        catch (e: Exception)
        {
            null
        }
    }

    fun theme(context: Context): Theme
    {
        val id = KakuUi.s(KakuUi.DICT_FONT_FAMILY) ?: ""
        val family = when
        {
            id.isEmpty() -> "sans-serif"
            id.startsWith("@") -> "\"${cssString(id.substring(1))}\", sans-serif"
            else -> "\"kaku-dict\", sans-serif"
        }
        val file = if (id.isNotEmpty() && !id.startsWith("@")) File(KakuFonts.dir(context), id).toURI().toString() else null
        return Theme(KakuUi.i(KakuUi.C_WIN_BG), KakuUi.i(KakuUi.C_DICT_TEXT), KakuUi.i(KakuUi.C_DICT_HEADWORD),
                KakuUi.i(KakuUi.C_DICT_READING), KakuUi.i(KakuUi.C_DICT_POS), KakuUi.WARN,
                KakuUi.i(KakuUi.DICT_FONT_SIZE) * context.resources.configuration.fontScale,
                KakuUi.i(KakuUi.DICT_HEAD_SCALE) / 100f, KakuUi.i(KakuUi.DICT_FONT_WEIGHT), family, file)
    }

    /** The page for [theme], with [styles] (dictionary title → its styles.css). */
    fun compose(theme: Theme, entries: List<DictLookup.Entry>, kanji: List<DictLookup.Kanji>, notice: String?,
                styles: Map<String, String>, options: Options = Options(),
                media: (dictionary: String, path: String) -> String? = { _, _ -> null }): String
    {
        this.media = media
        this.options = options
        val sb = StringBuilder(16 * 1024)
        sb.append("<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, user-scalable=no\">")
        sb.append("<style>").append(themeCss(theme))
        val used = HashSet<String>()
        for (e in entries) for (d in e.definitions) used.add(d.dictionary)
        for (k in kanji) used.add(k.dictionary)
        for ((title, css) in styles)
        {
            if (title in used && css.isNotBlank())
            {
                // Scoped to the dictionary's own definitions, as Yomitan scopes them.
                sb.append("\n@scope ([data-dictionary=\"").append(cssString(title)).append("\"]) {\n")
                        .append(css.replace("</", "<\\/")).append("\n}\n")
            }
        }
        sb.append(overrideCss())
        if (options.compact) sb.append(compactCss())
        sb.append("</style></head><body").append(if (options.compact) " class=\"compact\"" else "").append('>')
        options.back?.let { sb.append("<a class=\"nav-back\" href=\"").append(BACK_URL).append("\">← ").append(esc(it)).append("</a>") }

        if (notice != null)
        {
            sb.append("<div class=\"notice\">").append(esc(notice)).append("</div>")
        }
        for (e in entries) entry(sb, e)
        for (k in kanji) kanji(sb, k)
        sb.append("<script>")
        if (options.compact && options.maxSenses > 0) sb.append(compactScript(options.maxSenses))
        sb.append("function h(){KakuHost.height(Math.ceil(document.documentElement.getBoundingClientRect().height));}")
                .append("new ResizeObserver(h).observe(document.documentElement);h();")
                .append("</script></body></html>")
        return sb.toString()
    }

    /** The image resolver of the page being composed (pages are composed on the main thread). */
    private var media: (String, String) -> String? = { _, _ -> null }
    private var options = Options()

    // ---- words -----------------------------------------------------------------------------------

    private fun entry(sb: StringBuilder, e: DictLookup.Entry)
    {
        sb.append("<section class=\"entry\"><div class=\"head\"><span class=\"hw\">")
        for ((text, ruby) in furigana(e.expression, e.reading))
        {
            if (ruby == null) sb.append(esc(text))
            else sb.append("<ruby>").append(esc(text)).append("<rt>").append(esc(ruby)).append("</rt></ruby>")
        }
        sb.append("</span>")
        if (e.deinflection.isNotEmpty())
        {
            sb.append("<span class=\"chip line\">← ").append(esc(e.deinflection.joinToString(" · "))).append("</span>")
        }
        val termTags = LinkedHashSet<String>()
        for (d in e.definitions) for (t in d.termTags.split(' ')) if (t.isNotBlank()) termTags.add(t)
        for (t in termTags) sb.append("<span class=\"chip solid\">").append(esc(t)).append("</span>")
        sb.append("</div>")

        if (e.frequencies.isNotEmpty())
        {
            sb.append("<div class=\"chips\">")
            for (f in e.frequencies)
            {
                sb.append("<span class=\"chip line\"><span class=\"dim\">").append(esc(f.dictionary))
                        .append("</span> ").append(esc(f.display)).append("</span>")
            }
            sb.append("</div>")
        }
        for (p in e.pitches) pitch(sb, p, p.reading.ifEmpty { e.reading.ifEmpty { e.expression } })

        val several = e.definitions.size > 1
        var lastDict: String? = null
        var n = 0
        val shown = if (options.maxSenses > 0) e.definitions.take(options.maxSenses) else e.definitions
        for (d in shown)
        {
            if (d.dictionary != lastDict)
            {
                if (lastDict != null) sb.append("</div>")
                sb.append("<div class=\"dict\" data-dictionary=\"").append(escAttr(d.dictionary)).append("\">")
                sb.append("<span class=\"dict-label\">").append(esc(d.dictionary)).append("</span>")
                lastDict = d.dictionary
            }
            sb.append("<div class=\"def\">")
            if (several) sb.append("<span class=\"num\">").append(circled(++n)).append("</span>")
            sb.append("<div class=\"def-body\">")
            val tags = d.definitionTags.split(' ').filter { it.isNotBlank() }.distinct()
            if (tags.isNotEmpty())
            {
                sb.append("<div class=\"def-tags\">")
                for (t in tags) sb.append("<span class=\"chip solid small\">").append(esc(t)).append("</span>")
                sb.append("</div>")
            }
            glossary(sb, d.glossaryJson, d.dictionary)
            sb.append("</div></div>")
        }
        if (shown.size < e.definitions.size) sb.append("<div class=\"more\">…</div>")
        if (lastDict != null) sb.append("</div>")
        sb.append("</section>")
    }

    /** A definition's glossary: strings, `text`, `structured-content`, deinflection pointers. */
    private fun glossary(sb: StringBuilder, json: String, dictionary: String)
    {
        val parsed = try { JsonParser.parseString(json) } catch (e: Exception) { null } ?: return
        val items: List<JsonElement> = if (parsed.isJsonArray) parsed.asJsonArray.toList() else listOf(parsed)
        val parts = ArrayList<String>()
        for (item in items)
        {
            val one = StringBuilder()
            when
            {
                item.isJsonPrimitive -> one.append(esc(item.asString).replace("\n", "<br>"))
                item.isJsonArray ->
                {
                    val a = item.asJsonArray
                    if (a.size() > 0 && a[0].isJsonPrimitive) one.append("→ ").append(esc(a[0].asString))
                }
                item.isJsonObject ->
                {
                    val o = item.asJsonObject
                    when (o.get("type")?.asString)
                    {
                        "text" -> one.append(esc(o.get("text")?.asString ?: "").replace("\n", "<br>"))
                        "structured-content" -> structured(one, o.get("content"), dictionary)
                        "image" -> image(one, o, dictionary)
                        else -> {}
                    }
                }
            }
            if (one.isNotEmpty()) parts.add(one.toString())
        }
        when (parts.size)
        {
            0 -> {}
            1 -> sb.append("<div class=\"gloss\">").append(parts[0]).append("</div>")
            else ->
            {
                sb.append("<ul class=\"gloss-list\">")
                for (p in parts) sb.append("<li class=\"gloss\">").append(p).append("</li>")
                sb.append("</ul>")
            }
        }
    }

    // ---- structured content (Yomitan's StructuredContentGenerator) --------------------------------

    private val ELEMENTS = setOf("br", "ruby", "rt", "rp", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "div", "span", "ol", "ul", "li", "details", "summary", "a")

    private val STYLES = mapOf(
            "fontStyle" to "font-style", "fontWeight" to "font-weight", "fontSize" to "font-size",
            "color" to "color", "background" to "background", "backgroundColor" to "background-color",
            "textDecorationLine" to "text-decoration-line", "textDecorationStyle" to "text-decoration-style",
            "textDecorationColor" to "text-decoration-color", "borderColor" to "border-color",
            "borderStyle" to "border-style", "borderRadius" to "border-radius", "borderWidth" to "border-width",
            "clipPath" to "clip-path", "verticalAlign" to "vertical-align", "textAlign" to "text-align",
            "textEmphasis" to "text-emphasis", "textShadow" to "text-shadow", "margin" to "margin",
            "marginTop" to "margin-top", "marginLeft" to "margin-left", "marginRight" to "margin-right",
            "marginBottom" to "margin-bottom", "padding" to "padding", "paddingTop" to "padding-top",
            "paddingLeft" to "padding-left", "paddingRight" to "padding-right", "paddingBottom" to "padding-bottom",
            "wordBreak" to "word-break", "whiteSpace" to "white-space", "cursor" to "cursor",
            "listStyleType" to "list-style-type")

    /** Style properties whose bare numbers mean em (Yomitan's rule). */
    private val EM_NUMBERS = setOf("margin", "marginTop", "marginLeft", "marginRight", "marginBottom",
            "padding", "paddingTop", "paddingLeft", "paddingRight", "paddingBottom")

    private fun structured(sb: StringBuilder, node: JsonElement?, dictionary: String)
    {
        if (node == null || node.isJsonNull) return
        when
        {
            node.isJsonPrimitive -> sb.append(esc(node.asString).replace("\n", "<br>"))
            node.isJsonArray -> for (c in node.asJsonArray) structured(sb, c, dictionary)
            node.isJsonObject -> element(sb, node.asJsonObject, dictionary)
        }
    }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.num(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    private fun JsonObject.bool(k: String): Boolean = get(k)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    /**
     * An image (a structured-content `img`, or a glossary `image` item), after Yomitan: displayed
     * at its preferred size (else its own), in em or px as `sizeUnits` says; `monochrome` ones are a
     * mask filled with the text colour (Jitendex's rare-character glyphs), others an `<img>` with a
     * backdrop when `background` asks for one. `collapsed` ones fold into a details element.
     */
    private fun image(sb: StringBuilder, o: JsonObject, dictionary: String)
    {
        val alt = o.str("alt") ?: o.str("title") ?: ""
        val url = media(dictionary, o.str("path") ?: "")
        if (url == null)
        {
            if (alt.isNotEmpty()) sb.append("<span class=\"img-alt\">").append(esc(alt)).append("</span>")
            return
        }
        val w = o.num("width")
        val h = o.num("height")
        val pw = o.num("preferredWidth")
        val ph = o.num("preferredHeight")
        val inv = when
        {
            pw != null && ph != null && pw > 0 -> ph / pw
            w != null && h != null && w > 0 -> h / w
            else -> null
        }
        val usedW = pw ?: (if (ph != null && inv != null && inv > 0) ph / inv else w)
        val usedH = ph ?: (if (usedW != null && inv != null) usedW * inv else h)
        val unit = if (o.str("sizeUnits") == "em") "em" else "px"
        val style = StringBuilder()
        if (usedW != null) style.append("width:").append(num(usedW)).append(unit).append(';')
        if (usedH != null) style.append("height:").append(num(usedH)).append(unit).append(';')
        if (usedW != null && usedH != null && unit == "px")
        {
            // Too wide for the window: shrink, keeping the shape.
            style.append("max-width:100%;height:auto;aspect-ratio:").append(num(usedW)).append('/').append(num(usedH)).append(';')
        }
        o.str("verticalAlign")?.let { if (it.matches(Regex("[a-z-]+"))) style.append("vertical-align:").append(it).append(';') }
        o.str("border")?.let { if (safeCss(it)) style.append("border:").append(it).append(';') }
        o.str("borderRadius")?.let { if (safeCss(it)) style.append("border-radius:").append(it).append(';') }
        val rendering = o.str("imageRendering") ?: if (o.bool("pixelated")) "pixelated" else null
        if (rendering != null && rendering.matches(Regex("[a-z-]+"))) style.append("image-rendering:").append(rendering).append(';')
        val title = o.str("title")
        val collapsed = o.bool("collapsible") && o.bool("collapsed")
        if (collapsed) sb.append("<details class=\"img-fold\"><summary>").append(esc(alt.ifEmpty { "Image" })).append("</summary>")
        if (o.str("appearance") == "monochrome")
        {
            val u = cssString(url)
            sb.append("<span class=\"sc-img mono\" role=\"img\" style=\"").append(escAttr(style.toString()))
                    .append(escAttr("-webkit-mask-image:url(\"$u\");mask-image:url(\"$u\");")).append('"')
            if (alt.isNotEmpty()) sb.append(" aria-label=\"").append(escAttr(alt)).append('"')
            if (title != null) sb.append(" title=\"").append(escAttr(title)).append('"')
            sb.append("></span>")
        }
        else
        {
            sb.append("<img class=\"sc-img").append(if (o.bool("background")) " backdrop" else "").append("\" src=\"")
                    .append(escAttr(url)).append("\" alt=\"").append(escAttr(alt)).append("\" style=\"").append(escAttr(style.toString())).append('"')
            if (title != null) sb.append(" title=\"").append(escAttr(title)).append('"')
            sb.append('>')
        }
        if (collapsed) sb.append("</details>")
    }

    private fun num(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.ROOT, "%.3f", d).trimEnd('0')

    private fun safeCss(v: String) = v.none { it == ';' || it == '{' || it == '}' || it == '<' || it == '"' || it == '(' }

    private fun element(sb: StringBuilder, o: JsonObject, dictionary: String)
    {
        val tag = o.get("tag")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        if (tag == "img")
        {
            image(sb, o, dictionary)
            return
        }
        if (tag == "br")
        {
            sb.append("<br>")
            return
        }
        val name = if (tag in ELEMENTS) tag else "span"
        sb.append('<').append(name).append(" class=\"gloss-sc-").append(name).append('"')
        (o.get("data") as? JsonObject)?.entrySet()?.forEach { (k, v) ->
            if (v.isJsonPrimitive && k.matches(Regex("[A-Za-z0-9_-]+")))
            {
                sb.append(" data-sc-").append(k).append("=\"").append(escAttr(v.asString)).append('"')
            }
        }
        (o.get("style") as? JsonObject)?.let { st ->
            val css = StringBuilder()
            for ((k, v) in st.entrySet())
            {
                val prop = STYLES[k] ?: continue
                if (!v.isJsonPrimitive) continue
                val p = v.asJsonPrimitive
                val value = if (p.isNumber && k in EM_NUMBERS) "${p.asString}em" else p.asString
                if (value.any { it == ';' || it == '{' || it == '}' || it == '<' }) continue
                css.append(prop).append(':').append(value).append(';')
            }
            if (css.isNotEmpty()) sb.append(" style=\"").append(escAttr(css.toString())).append('"')
        }
        for (attr in listOf("title", "lang"))
        {
            o.get(attr)?.takeIf { it.isJsonPrimitive }?.let { sb.append(' ').append(attr).append("=\"").append(escAttr(it.asString)).append('"') }
        }
        if (name == "td" || name == "th")
        {
            o.get("colSpan")?.takeIf { it.isJsonPrimitive }?.let { sb.append(" colspan=\"").append(it.asInt).append('"') }
            o.get("rowSpan")?.takeIf { it.isJsonPrimitive }?.let { sb.append(" rowspan=\"").append(it.asInt).append('"') }
        }
        if (name == "details" && o.get("open")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) sb.append(" open")
        if (name == "a")
        {
            // "?query=…" links look the word up (the window follows them); outside links go nowhere.
            val href = o.get("href")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            if (href.startsWith("?")) sb.append(" class=\"link-internal\" href=\"").append(escAttr(href)).append('"')
            else sb.append(" class=\"link-external\"")
        }
        sb.append('>')
        structured(sb, o.get("content"), dictionary)
        sb.append("</").append(name).append('>')
    }

    // ---- pitch accent ----------------------------------------------------------------------------

    private const val SMALL_KANA = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ"

    fun morae(reading: String): List<String>
    {
        val out = ArrayList<String>()
        for (c in reading)
        {
            if (c in SMALL_KANA && out.isNotEmpty()) out[out.size - 1] = out.last() + c else out.add(c.toString())
        }
        return out
    }

    /** High (true) / low per mora, plus the following particle, for a downstep [position]. */
    fun pattern(morae: Int, position: Int): List<Boolean> = (0..morae).map { i ->
        when
        {
            position == 0 -> i > 0
            position == 1 -> i == 0
            else -> i in 1 until position
        }
    }

    private fun pitch(sb: StringBuilder, p: DictLookup.Pitch, reading: String)
    {
        val mora = morae(reading)
        for (pos in p.positions)
        {
            sb.append("<div class=\"pitch\"><span class=\"chip line\"><span class=\"dim\">").append(esc(p.dictionary))
                    .append("</span> [").append(esc(pos)).append("]</span>")
            val n = pos.toIntOrNull()
            if (n != null && mora.isNotEmpty() && n <= mora.size)
            {
                val w = 24
                val high = 6
                val low = 22
                val pat = pattern(mora.size, n)
                val width = w * pat.size
                sb.append("<span class=\"pitch-graph\"><svg width=\"").append(width).append("\" height=\"28\" viewBox=\"0 0 ")
                        .append(width).append(" 28\"><polyline class=\"pl\" points=\"")
                pat.forEachIndexed { i, h -> sb.append(i * w + w / 2).append(',').append(if (h) high else low).append(' ') }
                sb.append("\"/>")
                pat.forEachIndexed { i, h ->
                    sb.append("<circle class=\"").append(if (i < mora.size) "pd" else "pp").append("\" cx=\"")
                            .append(i * w + w / 2).append("\" cy=\"").append(if (h) high else low).append("\" r=\"3.6\"/>")
                }
                sb.append("</svg><span class=\"pitch-kana\">")
                for (m in mora) sb.append("<span style=\"width:").append(w).append("px\">").append(esc(m)).append("</span>")
                sb.append("</span></span>")
            }
            sb.append("</div>")
        }
    }

    // ---- kanji -----------------------------------------------------------------------------------

    private fun kanji(sb: StringBuilder, k: DictLookup.Kanji)
    {
        sb.append("<section class=\"entry kanji\" data-dictionary=\"").append(escAttr(k.dictionary)).append("\">")
        sb.append("<div class=\"head\"><span class=\"kanji-char\">").append(esc(k.character)).append("</span>")
        sb.append("<span class=\"dict-label\">").append(esc(k.dictionary)).append("</span></div>")
        if (k.onyomi.isNotBlank())
        {
            sb.append("<div class=\"kr\"><span class=\"chip solid small\">音</span>")
                    .append(esc(k.onyomi.trim().replace(' ', '・'))).append("</div>")
        }
        if (k.kunyomi.isNotBlank())
        {
            sb.append("<div class=\"kr\"><span class=\"chip solid small\">訓</span>")
                    .append(esc(k.kunyomi.trim().replace(' ', '・'))).append("</div>")
        }
        if (k.meanings.isNotEmpty())
        {
            sb.append("<div class=\"gloss\">").append(esc(k.meanings.joinToString("; "))).append("</div>")
        }
        val misc = ArrayList<Pair<String, String>>()
        val rest = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        k.stats.forEachIndexed { i, s ->
            val cat = k.statCategories.getOrNull(i) ?: ""
            if (cat == "misc" || cat.isEmpty()) misc.add(s) else rest.getOrPut(cat) { ArrayList() }.add(s)
        }
        if (misc.isNotEmpty() || k.frequencies.isNotEmpty())
        {
            sb.append("<div class=\"chips\">")
            for ((label, value) in misc)
            {
                sb.append("<span class=\"chip line\"><span class=\"dim\">").append(esc(label)).append("</span> ").append(esc(value)).append("</span>")
            }
            for (f in k.frequencies)
            {
                sb.append("<span class=\"chip line\"><span class=\"dim\">").append(esc(f.dictionary)).append("</span> ").append(esc(f.display)).append("</span>")
            }
            sb.append("</div>")
        }
        for ((cat, list) in rest)
        {
            sb.append("<details class=\"stats\"><summary>").append(esc(CATEGORY_NAMES[cat] ?: cat)).append("</summary><table>")
            for ((label, value) in list)
            {
                sb.append("<tr><th>").append(esc(label)).append("</th><td>").append(esc(value)).append("</td></tr>")
            }
            sb.append("</table></details>")
        }
        sb.append("</section>")
    }

    private val CATEGORY_NAMES = mapOf("index" to "Dictionary indices", "code" to "Codepoints / codes",
            "class" to "Classifications")

    // ---- furigana --------------------------------------------------------------------------------

    private fun isKana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c == 'ー' || c == 'ゝ' || c == 'ゞ' || c == 'ヽ' || c == 'ヾ'

    /**
     * The expression cut into (text, reading-or-null) parts: kana runs stand alone, each kanji run
     * gets the part of the reading between its neighbours (Yomitan's distributeFurigana, simplified).
     * When the reading does not fit, the whole expression carries the whole reading.
     */
    fun furigana(expression: String, reading: String): List<Pair<String, String?>>
    {
        if (reading.isEmpty() || reading == expression) return listOf(expression to null)
        val groups = ArrayList<Pair<String, Boolean>>()
        for (c in expression)
        {
            val kana = isKana(c)
            if (groups.isNotEmpty() && groups.last().second == kana) groups[groups.size - 1] = (groups.last().first + c) to kana
            else groups.add(c.toString() to kana)
        }
        if (groups.none { it.second }) return listOf(expression to reading)
        val pattern = StringBuilder("^")
        for ((text, kana) in groups) pattern.append(if (kana) Regex.escape(DictLookup.kataToHira(text)) else "(.+?)")
        pattern.append('$')
        val m = Regex(pattern.toString()).find(DictLookup.kataToHira(reading)) ?: return listOf(expression to reading)
        val out = ArrayList<Pair<String, String?>>()
        var g = 1
        for ((text, kana) in groups)
        {
            if (kana) out.add(text to null)
            else
            {
                val r = m.groups[g++]!!.range
                out.add(text to reading.substring(r.first, r.last + 1))
            }
        }
        return out
    }

    // ---- css -------------------------------------------------------------------------------------

    private fun rgba(c: Int): String = String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
            (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, ((c ushr 24) and 0xFF) / 255f)

    private fun themeCss(t: Theme): String
    {
        val sb = StringBuilder()
        if (t.fontFileUrl != null)
        {
            sb.append("@font-face{font-family:\"kaku-dict\";src:url(\"").append(cssString(t.fontFileUrl)).append("\");}")
        }
        sb.append(":root{")
                .append("--bg:").append(rgba(t.bg)).append(';')
                .append("--text:").append(rgba(t.text)).append(';')
                .append("--head:").append(rgba(t.head)).append(';')
                .append("--reading:").append(rgba(t.reading)).append(';')
                .append("--pos:").append(rgba(t.pos)).append(';')
                .append("--warn:").append(rgba(t.warn)).append(';')
                .append("--head-scale:").append(t.headScale).append(';')
                .append("--text-color:var(--text);--fg:var(--text);--background-color:transparent;")
                .append("--font-size-no-units:").append(t.sizePx.toInt()).append(';')
                .append("}")
        sb.append("html,body{margin:0;padding:0;background:transparent;color:var(--text);")
                .append("font-family:").append(t.family).append(";font-weight:").append(t.weight)
                .append(";font-size:").append(String.format(Locale.ROOT, "%.1f", t.sizePx)).append("px;line-height:1.45;")
                .append("-webkit-text-size-adjust:none;overflow-wrap:anywhere;-webkit-tap-highlight-color:transparent;}")
        sb.append("""
body{padding:2px 0 4px}
.entry+.entry{margin-top:.9em;padding-top:.7em;border-top:1px solid color-mix(in srgb,var(--pos) 45%,transparent)}
.head{display:flex;flex-wrap:wrap;align-items:flex-end;gap:.25em .45em;margin-bottom:.15em}
.hw{color:var(--head);font-size:calc(1.7em * var(--head-scale));font-weight:bold;line-height:1.15}
.hw rt{color:var(--reading);font-size:.42em;font-weight:normal}
.chips{margin:.1em 0 .2em}
.chip{display:inline-block;border-radius:999px;padding:0 .55em;font-size:.8em;line-height:1.5;margin:.1em .3em .1em 0;white-space:nowrap}
.chip.line{border:1px solid var(--pos);color:var(--text)}
.chip.solid{background:var(--pos);color:var(--bg);font-weight:bold;border:1px solid var(--pos)}
.chip.small{font-size:.72em;padding:0 .45em}
.dim{color:var(--pos)}
.pitch{display:flex;align-items:center;flex-wrap:wrap;gap:.2em .6em;margin:.1em 0}
.pitch-graph{display:inline-flex;flex-direction:column;align-items:flex-start}
.pitch-graph svg{display:block}
.pitch-graph .pl{fill:none;stroke:var(--reading);stroke-width:1.8}
.pitch-graph .pd{fill:var(--reading);stroke:var(--reading);stroke-width:1.5}
.pitch-graph .pp{fill:var(--bg);stroke:var(--reading);stroke-width:1.5}
.pitch-kana{display:flex;color:var(--reading);font-size:.85em;line-height:1.2}
.pitch-kana span{display:inline-block;text-align:center}
.dict{margin-top:.35em}
.dict-label{display:inline-block;background:var(--pos);color:var(--bg);font-size:.7em;font-weight:bold;border-radius:.25em;padding:0 .45em;margin:.2em 0 .15em}
.def{display:flex;gap:.3em;margin:.1em 0}
.num{flex:none;color:var(--pos)}
.def-body{flex:1;min-width:0}
.def-tags{margin-bottom:.05em}
.gloss{white-space:normal}
ul.gloss-list{margin:0;padding-left:1.1em}
ul,ol{margin:0;padding-left:1.3em}
ruby rt{color:var(--reading)}
.gloss-sc-table{border-collapse:collapse}
.gloss-sc-td,.gloss-sc-th{border:1px solid color-mix(in srgb,var(--pos) 60%,transparent);padding:.1em .3em}
.img-alt{color:var(--pos)}
.sc-img{display:inline-block;max-width:100%;vertical-align:middle}
.sc-img.mono{background-color:currentColor;-webkit-mask-size:contain;mask-size:contain;-webkit-mask-repeat:no-repeat;mask-repeat:no-repeat;-webkit-mask-position:center;mask-position:center;vertical-align:-0.12em}
.sc-img.backdrop{background:var(--text);border-radius:.3em;padding:.2em;box-sizing:content-box}
details.img-fold summary{color:var(--pos);font-size:.85em}
.kanji-char{color:var(--head);font-size:calc(2.4em * var(--head-scale));line-height:1.1}
.kr{margin:.1em 0}
details.stats{margin:.2em 0}
details.stats summary{color:var(--pos);font-size:.85em}
details.stats table{border-collapse:collapse;font-size:.8em}
details.stats th{text-align:left;font-weight:normal;color:var(--pos);padding:0 .6em 0 0}
.notice{color:var(--warn)}
.nav-back{display:inline-block;border:1px solid var(--pos);border-radius:999px;padding:0 .7em;margin:0 0 .5em;color:var(--text);text-decoration:none;font-size:.9em}
.more{color:var(--pos)}
""")
        return sb.toString()
    }

    /**
     * The theme layer after the dictionaries' own styles: every tag chip, box and link in the
     * 白い熊 画 UI colours (Jitendex paints its chips grey / brown / purple / green and its boxes in
     * their own colours; here they all follow the dictionary colours of the UI page).
     */
    private fun overrideCss(): String = """
[data-dictionary] span[data-sc-class="tag"],
[data-dictionary] span[data-sc-content="part-of-speech-info"],
[data-dictionary] span[data-sc-content="misc-info"],
[data-dictionary] span[data-sc-content="field-info"],
[data-dictionary] span[data-sc-content="dialect-info"],
[data-dictionary] span[data-sc-content="forms-label"],
[data-dictionary] span[data-sc-content="lang-source-wasei"]{background:var(--pos) !important;color:var(--bg) !important;border-color:var(--pos) !important}
[data-dictionary] div[data-sc-class="extra-box"]{border-color:var(--pos) !important;background:color-mix(in srgb,var(--pos) 9%,transparent) !important}
[data-dictionary] span[data-sc-content="reference-label"],
[data-dictionary] div[data-sc-class="extra-label"]{color:var(--pos) !important}
[data-dictionary] a,[data-dictionary] .gloss-sc-a{color:var(--reading) !important;text-decoration:underline;text-decoration-color:var(--pos)}
[data-dictionary] div[data-sc-content="attribution"]{color:var(--pos) !important;font-size:.72em;margin-top:.4em}
[data-dictionary] span[data-sc-content="example-keyword"]{color:var(--head);font-weight:bold;text-decoration:underline;text-decoration-thickness:.12em;text-underline-offset:.18em;text-decoration-color:var(--head)}
[data-dictionary] span[data-sc-content="example-keyword"] rt{font-weight:normal;color:var(--reading)}
[data-dictionary] div[data-sc-content="example-sentence-b"]{color:var(--pos)}
[data-dictionary] span[data-sc-content="attribution-footnote"]{color:var(--pos);font-size:.7em;vertical-align:super;margin-left:.2em}
"""

    /** The popup: the gist only — no example / note / cross-reference boxes, sources or tables; pictures stay. */
    private fun compactCss(): String = """
body.compact [data-sc-content="attribution"],
body.compact [data-sc-content="forms"],
body.compact [data-sc-content="example-sentence"],
body.compact [data-sc-content="xref"],
body.compact [data-sc-content="antonym"],
body.compact [data-sc-content="sense-note"],
body.compact [data-sc-content="info-gloss"],
body.compact [data-sc-content="lang-source"],
body.compact [data-sc-content="graphic-attribution"],
body.compact details.stats,
body.compact .pitch .chip{display:none !important}
body.compact .hw{font-size:calc(1.35em * var(--head-scale))}
body.compact .entry+.entry{margin-top:.5em;padding-top:.4em}
body.compact .sc-img:not(.mono){max-height:7em !important;width:auto !important;height:auto !important}
"""

    /**
     * The popup's sense limit inside a dictionary's own sense lists (Jitendex numbers ①② within one
     * definition, over several part-of-speech groups): per word, senses past [max] are hidden, then
     * groups left empty, and a "…" says there is more.
     */
    private fun compactScript(max: Int): String = """
document.querySelectorAll('section.entry').forEach(function(e){
 var n=0,cut=false;
 e.querySelectorAll('li[data-sc-content="sense"]').forEach(function(li){if(++n>$max){li.style.display='none';cut=true;}});
 e.querySelectorAll('li[data-sc-content="sense-group"]').forEach(function(g){
  var any=false;g.querySelectorAll('li[data-sc-content="sense"]').forEach(function(li){if(li.style.display!=='none')any=true;});
  if(!any&&g.querySelector('li[data-sc-content="sense"]'))g.style.display='none';});
 if(cut&&!e.querySelector('.more')){var m=document.createElement('div');m.className='more';m.textContent='…';e.appendChild(m);}
});
"""

    // ---- helpers ---------------------------------------------------------------------------------

    private fun circled(n: Int): String = if (n in 1..20) (0x2460 + n - 1).toChar().toString() else "($n)"

    private fun esc(s: String): String
    {
        val sb = StringBuilder(s.length + 8)
        for (c in s)
        {
            when (c)
            {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun escAttr(s: String): String = esc(s).replace("\"", "&quot;")

    private fun cssString(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
