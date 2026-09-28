package shiroikuma.kaku.dict

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Parses real Yomitan dictionaries when they are present (the developer's scratch copies; skipped
 * otherwise): every row reaches the sink, and a few known entries come out whole.
 * Set KAKU_DICT_DIR to the folder holding JMdict_english.zip / KANJIDIC_english.zip / JPDB.zip.
 */
class YomitanReaderTest
{
    private val dir = System.getenv("KAKU_DICT_DIR")?.let { File(it) }

    private class Counter : YomitanReader.Sink
    {
        var terms = 0; var termMeta = 0; var kanji = 0; var kanjiMeta = 0; var tags = 0
        val found = HashMap<String, String>()
        override fun term(expression: String, reading: String, definitionTags: String, rules: String, score: Int,
                          glossaryJson: String, sequence: Long, termTags: String)
        {
            terms++
            if (expression == "食べる" && !found.containsKey("食べる")) found["食べる"] = "$reading|$rules|${glossaryJson.take(200)}"
        }
        override fun termMeta(expression: String, mode: String, dataJson: String) { termMeta++ }
        override fun kanji(character: String, onyomi: String, kunyomi: String, tags: String, meaningsJson: String, statsJson: String)
        {
            kanji++
            if (character == "画") found["画"] = "$onyomi|$kunyomi|$meaningsJson|${statsJson.take(120)}"
        }
        override fun kanjiMeta(character: String, mode: String, dataJson: String) { kanjiMeta++ }
        override fun tag(name: String, category: String, order: Int, notes: String, score: Int) { tags++ }
    }

    @Test
    fun readsJmdict()
    {
        val zip = dir?.let { File(it, "JMdict_english.zip") }
        assumeTrue(zip != null && zip.isFile)
        val c = Counter()
        val t0 = System.currentTimeMillis()
        val index = YomitanReader(zip!!).read(c)
        println("JMdict: ${index.title} — ${c.terms} terms, ${c.tags} tags in ${System.currentTimeMillis() - t0} ms; 食べる = ${c.found["食べる"]}")
        assertTrue(c.terms > 400_000)
        assertTrue(c.found["食べる"]!!.startsWith("たべる|v1"))
    }

    @Test
    fun readsKanjidicAndFrequencies()
    {
        val kd = dir?.let { File(it, "KANJIDIC_english.zip") }
        assumeTrue(kd != null && kd.isFile)
        val c = Counter()
        YomitanReader(kd!!).read(c)
        println("KANJIDIC: ${c.kanji} kanji; 画 = ${c.found["画"]}")
        assertTrue(c.kanji > 10_000)
        val jpdb = File(dir, "JPDB.zip")
        if (jpdb.isFile)
        {
            val f = Counter()
            val idx = YomitanReader(jpdb).read(f)
            println("JPDB: ${idx.title} — ${f.termMeta} frequency rows")
            assertTrue(f.termMeta > 100_000)
        }
    }
}
