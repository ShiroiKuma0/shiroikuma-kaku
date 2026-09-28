package shiroikuma.kaku.dict

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * Reads a Yomitan dictionary zip (format 3, and the legacy format 1/2 term / kanji rows) and hands
 * every row to a [Sink] — streaming each bank with a JsonReader, so a 500 MB Jitendex never sits
 * in memory. Knows nothing about storage; [DictImport] writes the rows into [DictDb].
 *
 * Banks: `term_bank_N.json` `[expression, reading, definitionTags, rules, score, glossary[],
 * sequence, termTags]`, `term_meta_bank_N.json` `[term, mode (freq|pitch|ipa), data]`,
 * `kanji_bank_N.json` `[character, onyomi, kunyomi, tags, meanings[], stats{}]`,
 * `kanji_meta_bank_N.json`, `tag_bank_N.json` `[name, category, order, notes, score]`, plus
 * `styles.css`. Images are not read (the renderer shows text).
 */
class YomitanReader(private val zipFile: File)
{
    class Index(val title: String, val revision: String, val format: Int, val sequenced: Boolean,
                val author: String?, val url: String?, val description: String?, val attribution: String?,
                val sourceLanguage: String?, val targetLanguage: String?, val frequencyMode: String?,
                val isUpdatable: Boolean, val indexUrl: String?, val downloadUrl: String?)

    interface Sink
    {
        fun term(expression: String, reading: String, definitionTags: String, rules: String, score: Int,
                 glossaryJson: String, sequence: Long, termTags: String)
        fun termMeta(expression: String, mode: String, dataJson: String)
        fun kanji(character: String, onyomi: String, kunyomi: String, tags: String, meaningsJson: String, statsJson: String)
        fun kanjiMeta(character: String, mode: String, dataJson: String)
        fun tag(name: String, category: String, order: Int, notes: String, score: Int)
        /** How far through the banks, for progress: [done] of [total] bank files. */
        fun bank(done: Int, total: Int, name: String) {}
    }

    /** Reads only index.json — to show / check a dictionary before importing it. */
    fun index(): Index
    {
        ZipFile(zipFile).use { zip -> return readIndex(zip) }
    }

    private fun readIndex(zip: ZipFile): Index
    {
        val entry = zip.getEntry("index.json") ?: throw IOException("not a Yomitan dictionary (no index.json)")
        val o = zip.getInputStream(entry).use { JsonParser.parseReader(InputStreamReader(it, Charsets.UTF_8)).asJsonObject }
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
        val title = s("title") ?: throw IOException("index.json has no title")
        val format = (o.get("format") ?: o.get("version"))?.asInt ?: 3
        return Index(title, s("revision") ?: "", format, o.get("sequenced")?.asBoolean ?: false,
                s("author"), s("url"), s("description"), s("attribution"), s("sourceLanguage"), s("targetLanguage"),
                s("frequencyMode"), o.get("isUpdatable")?.asBoolean ?: false, s("indexUrl"), s("downloadUrl"))
    }

    /** The dictionary's own styles.css, if it has one. */
    fun styles(): String?
    {
        ZipFile(zipFile).use { zip ->
            val e = zip.getEntry("styles.css") ?: return null
            return zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    fun read(sink: Sink): Index
    {
        ZipFile(zipFile).use { zip ->
            val index = readIndex(zip)
            val banks = zip.entries().asSequence().map { it.name }
                    .filter { BANK.matches(it) }
                    .sortedWith(compareBy({ bankOrder(it) }, { bankNumber(it) }))
                    .toList()
            for ((i, name) in banks.withIndex())
            {
                sink.bank(i, banks.size, name)
                val kind = BANK.matchEntire(name)!!.groupValues[1]
                zip.getInputStream(zip.getEntry(name)).use { input ->
                    JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        reader.beginArray()
                        while (reader.hasNext())
                        {
                            val row = JsonParser.parseReader(reader)
                            if (row.isJsonArray) handleRow(kind, row.asJsonArray, index.format, sink)
                        }
                        reader.endArray()
                    }
                }
            }
            sink.bank(banks.size, banks.size, "")
            return index
        }
    }

    private fun handleRow(kind: String, r: JsonArray, format: Int, sink: Sink)
    {
        when (kind)
        {
            "term" ->
            {
                if (format >= 3)
                {
                    sink.term(str(r, 0), str(r, 1), str(r, 2), str(r, 3), int(r, 4),
                            r.get(5).toString(), long(r, 6), str(r, 7))
                }
                else
                {
                    // Legacy rows: [expression, reading, tags, rules, score, glossary…] — the glossary is the tail.
                    val glossary = JsonArray()
                    for (i in 5 until r.size()) glossary.add(r.get(i))
                    sink.term(str(r, 0), str(r, 1), str(r, 2), str(r, 3), int(r, 4), glossary.toString(), 0, "")
                }
            }
            "term_meta" -> sink.termMeta(str(r, 0), str(r, 1), r.get(2).toString())
            "kanji" ->
            {
                if (format >= 3)
                {
                    sink.kanji(str(r, 0), str(r, 1), str(r, 2), str(r, 3), r.get(4).toString(),
                            (if (r.size() > 5) r.get(5) else JsonObject()).toString())
                }
                else
                {
                    val meanings = JsonArray()
                    for (i in 4 until r.size()) meanings.add(r.get(i))
                    sink.kanji(str(r, 0), str(r, 1), str(r, 2), str(r, 3), meanings.toString(), "{}")
                }
            }
            "kanji_meta" -> sink.kanjiMeta(str(r, 0), str(r, 1), r.get(2).toString())
            "tag" -> sink.tag(str(r, 0), str(r, 1), int(r, 2), str(r, 3), int(r, 4))
        }
    }

    companion object
    {
        private val BANK = Regex("(term|term_meta|kanji|kanji_meta|tag)_bank_(\\d+)\\.json")

        private fun bankOrder(name: String) = when (BANK.matchEntire(name)!!.groupValues[1])
        {
            "tag" -> 0
            "term" -> 1
            "term_meta" -> 2
            "kanji" -> 3
            else -> 4
        }

        private fun bankNumber(name: String) = BANK.matchEntire(name)!!.groupValues[2].toInt()

        private fun str(r: JsonArray, i: Int): String
        {
            if (i >= r.size()) return ""
            val e: JsonElement = r.get(i)
            return if (e.isJsonNull) "" else if (e.isJsonPrimitive) e.asString else e.toString()
        }

        private fun int(r: JsonArray, i: Int): Int =
                if (i < r.size() && r.get(i).isJsonPrimitive && r.get(i).asJsonPrimitive.isNumber) r.get(i).asNumber.toInt() else 0

        private fun long(r: JsonArray, i: Int): Long =
                if (i < r.size() && r.get(i).isJsonPrimitive && r.get(i).asJsonPrimitive.isNumber) r.get(i).asNumber.toLong() else 0
    }
}
