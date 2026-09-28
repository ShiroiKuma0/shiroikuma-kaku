package shiroikuma.kaku.dict

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * Looks text up in the imported Yomitan dictionaries, the way Yomitan scans: every prefix of the
 * text from the looked-up character, longest first (at most [MAX_LENGTH] characters), in a few
 * script variants (as written, katakana ↔ hiragana, half-width katakana → full width), each run
 * through the ported [Deinflector]. All candidate forms are fetched in one pass; a dictionary row
 * counts when its expression or reading is a candidate and its parts of speech agree with how the
 * candidate was deinflected. Rows are grouped per word (expression + reading) and ranked: the
 * longest source text first, then the shortest deinflection, then frequency, then the dictionary's
 * own score and the dictionaries' order.
 */
class DictLookup(context: Context)
{
    data class Definition(val dictionary: String, val dictPriority: Int, val definitionTags: String, val termTags: String,
                          val rules: String, val score: Int, val glossaryJson: String, val sequence: Long)

    data class Frequency(val dictionary: String, val value: Long, val display: String)

    data class Pitch(val dictionary: String, val reading: String, val positions: List<String>)

    data class Entry(val expression: String, val reading: String, val sourceText: String,
                     val deinflection: List<String>, val definitions: List<Definition>,
                     val frequencies: List<Frequency>, val pitches: List<Pitch>)

    data class Kanji(val dictionary: String, val character: String, val onyomi: String, val kunyomi: String,
                     val meanings: List<String>, val stats: List<Pair<String, String>>, val frequencies: List<Frequency>,
                     /** Each stat's tag category (misc, index, code, class …), parallel to [stats]. */
                     val statCategories: List<String> = emptyList())

    private val app = context.applicationContext
    private val db = DictDb.get(app)

    private class Candidate(val deinflected: Deinflector.Result, val source: String)

    fun lookup(text: String): List<Entry>
    {
        val deinflector = deinflector(app)
        val codePoints = text.codePoints().toArray()
        val maxLen = minOf(MAX_LENGTH, codePoints.size)
        val candidates = HashMap<String, MutableList<Candidate>>()
        for (len in maxLen downTo 1)
        {
            val source = String(codePoints, 0, len)
            for (variant in variants(source))
            {
                for (d in deinflector.deinflect(variant))
                {
                    candidates.getOrPut(d.text) { ArrayList() }.add(Candidate(d, source))
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val dicts = db.dictionaries().associateBy { it.id }
        class Hit(val row: DictDb.TermRow, val cand: Candidate)
        val hits = ArrayList<Hit>()
        for (row in db.termsFor(candidates.keys))
        {
            var best: Candidate? = null
            for (key in listOf(row.expression, row.reading))
            {
                for (c in candidates[key] ?: continue)
                {
                    if (!deinflector.entryMatches(c.deinflected, row.rules)) continue
                    if (best == null || c.source.length > best.source.length ||
                            (c.source.length == best.source.length && c.deinflected.trace.size < best.deinflected.trace.size))
                    {
                        best = c
                    }
                }
            }
            if (best != null) hits.add(Hit(row, best))
        }
        if (hits.isEmpty()) return emptyList()

        // Group per word; the word's source and deinflection are its best hit's.
        val groups = LinkedHashMap<Pair<String, String>, MutableList<Hit>>()
        for (h in hits) groups.getOrPut(h.row.expression to h.row.reading) { ArrayList() }.add(h)

        val meta = db.termMetaFor(groups.keys.map { it.first }.distinct())
        val entries = groups.map { (key, list) ->
            val best = list.maxWith(compareBy<Hit>({ it.cand.source.length }, { -it.cand.deinflected.trace.size }))
            val definitions = list.sortedWith(compareBy<Hit>({ dicts[it.row.dict]?.priority ?: 0 }, { -it.row.score }, { it.row.sequence }))
                    .map { h ->
                        val d = dicts[h.row.dict]
                        Definition(d?.title ?: "", d?.priority ?: 0, h.row.definitionTags, h.row.termTags, h.row.rules,
                                h.row.score, h.row.glossaryJson, h.row.sequence)
                    }
            val (freqs, pitches) = metaFor(meta, key.first, key.second, dicts)
            Entry(key.first, key.second, best.cand.source, best.cand.deinflected.trace.map { deinflector.nameOf(it) },
                    definitions, freqs, pitches)
        }
        return entries.sortedWith(compareBy<Entry>(
                { -it.sourceText.length },
                { it.deinflection.size },
                { it.frequencies.minOfOrNull { f -> f.value } ?: Long.MAX_VALUE },
                { -(it.definitions.maxOfOrNull { d -> d.score } ?: 0) },
                { it.definitions.minOfOrNull { d -> d.dictPriority } ?: 0 }))
                .take(MAX_ENTRIES)
    }

    /** KANJIDIC-style information for one character, from every enabled kanji dictionary. */
    fun kanji(character: String): List<Kanji>
    {
        val dicts = db.dictionaries().associateBy { it.id }
        val tags = db.tags()
        val meta = db.kanjiMetaFor(character)
        return db.kanjiFor(character).map { k ->
            val meanings = try { JsonParser.parseString(k.meaningsJson).asJsonArray.map { it.asString } } catch (e: Exception) { emptyList() }
            val categories = ArrayList<String>()
            val stats = try
            {
                JsonParser.parseString(k.statsJson).asJsonObject.entrySet().map { (name, v) ->
                    val tag = tags[k.dict]?.get(name)
                    categories.add(tag?.category ?: "")
                    val label = tag?.notes?.takeIf { it.isNotEmpty() } ?: name
                    label to (if (v.isJsonPrimitive) v.asString else v.toString())
                }
            }
            catch (e: Exception) { categories.clear(); emptyList() }
            val freqs = meta.filter { it.mode == "freq" }.mapNotNull { m -> frequency(m.dataJson, null)?.let { (v, s) -> Frequency(dicts[m.dict]?.title ?: "", v, s) } }
            Kanji(dicts[k.dict]?.title ?: "", k.character, k.onyomi, k.kunyomi, meanings, stats, freqs, categories)
        }
    }

    private fun metaFor(meta: List<DictDb.MetaRow>, expression: String, reading: String,
                        dicts: Map<Long, DictDb.Dictionary>): Pair<List<Frequency>, List<Pitch>>
    {
        val freqs = ArrayList<Frequency>()
        val pitches = ArrayList<Pitch>()
        for (m in meta)
        {
            if (m.key != expression) continue
            val title = dicts[m.dict]?.title ?: ""
            when (m.mode)
            {
                "freq" -> frequency(m.dataJson, reading)?.let { (v, s) -> freqs.add(Frequency(title, v, s)) }
                "pitch" -> try
                {
                    val o = JsonParser.parseString(m.dataJson).asJsonObject
                    val r = o.get("reading")?.asString ?: ""
                    if (r == reading || r.isEmpty())
                    {
                        val positions = o.getAsJsonArray("pitches")?.map { p -> p.asJsonObject.get("position").toString().trim('"') } ?: emptyList()
                        pitches.add(Pitch(title, r, positions))
                    }
                }
                catch (ignored: Exception) {}
            }
        }
        return freqs.sortedBy { dicts.values.firstOrNull { d -> d.title == it.dictionary }?.priority ?: 0 } to pitches
    }

    /** A frequency value in any of Yomitan's shapes, filtered by [reading] when it names one. */
    private fun frequency(json: String, reading: String?): Pair<Long, String>?
    {
        return try
        {
            var e: JsonElement = JsonParser.parseString(json)
            if (e.isJsonObject && e.asJsonObject.has("frequency"))
            {
                val r = e.asJsonObject.get("reading")?.asString
                if (reading != null && r != null && r != reading) return null
                e = e.asJsonObject.get("frequency")
            }
            when
            {
                e.isJsonPrimitive && e.asJsonPrimitive.isNumber -> e.asLong to e.asString
                e.isJsonPrimitive -> (e.asString.filter { it.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE) to e.asString
                e.isJsonObject ->
                {
                    val o = e.asJsonObject
                    val v = o.get("value")?.asLong ?: Long.MAX_VALUE
                    v to (o.get("displayValue")?.asString ?: v.toString())
                }
                else -> null
            }
        }
        catch (ex: Exception)
        {
            null
        }
    }

    companion object
    {
        const val MAX_LENGTH = 20
        const val MAX_ENTRIES = 32

        @Volatile private var sDeinflector: Deinflector? = null

        fun deinflector(context: Context): Deinflector = sDeinflector ?: synchronized(this) {
            sDeinflector ?: Deinflector(context.assets.open("yomitan/japanese-transforms.json").use {
                it.readBytes().toString(Charsets.UTF_8)
            }).also { sDeinflector = it }
        }

        /** The text as written, katakana → hiragana, hiragana → katakana, half-width katakana → full width. */
        fun variants(s: String): Set<String>
        {
            val out = LinkedHashSet<String>()
            out.add(s)
            val full = halfToFullKatakana(s)
            out.add(full)
            out.add(kataToHira(full))
            out.add(hiraToKata(full))
            return out
        }

        fun kataToHira(s: String): String = buildString {
            for (c in s) append(if (c in 'ァ'..'ヶ') (c - 0x60) else c)
        }

        fun hiraToKata(s: String): String = buildString {
            for (c in s) append(if (c in 'ぁ'..'ゖ') (c + 0x60) else c)
        }

        private const val HALF = "ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
        private const val FULL = "ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン"

        fun halfToFullKatakana(s: String): String
        {
            if (s.none { it in '｡'..'ﾟ' }) return s
            val sb = StringBuilder()
            var i = 0
            while (i < s.length)
            {
                val c = s[i]
                val k = HALF.indexOf(c)
                if (k < 0) { sb.append(c); i++; continue }
                var f = FULL[k]
                val next = if (i + 1 < s.length) s[i + 1] else ' '
                if (next == 'ﾞ' && "カキクケコサシスセソタチツテトハヒフヘホウ".indexOf(f) >= 0)
                {
                    f = if (f == 'ウ') 'ヴ' else f + 1
                    i++
                }
                else if (next == 'ﾟ' && "ハヒフヘホ".indexOf(f) >= 0)
                {
                    f += 2
                    i++
                }
                sb.append(f)
                i++
            }
            return sb.toString()
        }
    }
}
