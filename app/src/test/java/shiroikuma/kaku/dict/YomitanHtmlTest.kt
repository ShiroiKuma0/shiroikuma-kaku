package shiroikuma.kaku.dict

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class YomitanHtmlTest
{
    @Test
    fun furigana()
    {
        assertEquals(listOf("食" to "た", "べる" to null), YomitanHtml.furigana("食べる", "たべる"))
        assertEquals(listOf("お" to null, "茶" to "ちゃ"), YomitanHtml.furigana("お茶", "おちゃ"))
        assertEquals(listOf("取" to "と", "り" to null, "扱" to "あつか", "い" to null), YomitanHtml.furigana("取り扱い", "とりあつかい"))
        assertEquals(listOf("今日" to "きょう"), YomitanHtml.furigana("今日", "きょう"))
        assertEquals(listOf("コーヒー" to null), YomitanHtml.furigana("コーヒー", "コーヒー"))
        // A reading that does not fit keeps the whole word together.
        assertEquals(listOf("食べる" to "のむ"), YomitanHtml.furigana("食べる", "のむ"))
    }

    @Test
    fun pitch()
    {
        assertEquals(listOf("きょ", "う"), YomitanHtml.morae("きょう"))
        // たべる [2]: low, high, low; particle low.
        assertEquals(listOf(false, true, false, false), YomitanHtml.pattern(3, 2))
        // heiban: low then high, particle high.
        assertEquals(listOf(false, true, true, true), YomitanHtml.pattern(3, 0))
        // atamadaka.
        assertEquals(listOf(true, false, false, false), YomitanHtml.pattern(3, 1))
        // odaka: the particle drops.
        assertEquals(listOf(false, true, true, false), YomitanHtml.pattern(3, 3))
    }

    /**
     * Renders Jitendex's 食べる to `KAKU_HTML_OUT` for a look in a browser; needs `KAKU_DICT_DIR`
     * holding `jitendex-yomitan.zip`.
     */
    @Test
    fun jitendexPage()
    {
        val dir = System.getenv("KAKU_DICT_DIR")
        val out = System.getenv("KAKU_HTML_OUT")
        assumeTrue(dir != null && out != null && File(dir, "jitendex-yomitan.zip").isFile)
        val zip = ZipFile(File(dir, "jitendex-yomitan.zip"))
        val title = JsonParser.parseString(zip.getInputStream(zip.getEntry("index.json")).reader().readText()).asJsonObject.get("title").asString
        val css = zip.getInputStream(zip.getEntry("styles.css")).reader().readText()
        val words = (System.getenv("KAKU_WORDS") ?: "食べる,掛ける,行く").split(',')
        val entries = ArrayList<DictLookup.Entry>()
        for (w in words)
        {
            val defs = ArrayList<DictLookup.Definition>()
            var reading = ""
            for (e in zip.entries())
            {
                if (!e.name.startsWith("term_bank")) continue
                for (row in JsonParser.parseString(zip.getInputStream(e).reader().readText()).asJsonArray)
                {
                    val r = row.asJsonArray
                    if (r[0].asString != w) continue
                    if (reading.isEmpty()) reading = r[1].asString
                    if (r[1].asString != reading) continue
                    defs.add(DictLookup.Definition(title, 0, r[2].asString, r[7].asString, r[3].asString, r[4].asInt,
                            r[5].toString(), r[6].asLong))
                }
            }
            entries.add(DictLookup.Entry(w, reading, w, if (w == "食べる") listOf("-て") else emptyList(), defs,
                    listOf(DictLookup.Frequency("JPDBv2㋕", 184, "184")),
                    if (w == "食べる") listOf(DictLookup.Pitch("NHK", "たべる", listOf("2"))) else emptyList()))
        }
        val theme = YomitanHtml.Theme(0xFF000000.toInt(), 0xFFFFFF00.toInt(), 0xFFFFFF00.toInt(), 0xFFFFFF00.toInt(),
                0xFFC8C800.toInt(), 0xFFFF5252.toInt(), 15f, 1.2f, 400, "sans-serif", null)
        val kanji = listOf(DictLookup.Kanji("KANJIDIC", "食", "ショク ジキ", "く.う た.べる は.む", listOf("eat", "food"),
                listOf("Stroke count" to "9", "Grade" to "2", "Nelson" to "5200", "Unicode" to "98df"), emptyList(),
                listOf("misc", "misc", "index", "code")))
        // The images, as the import lays them out: <media dir>/<zip path>.
        val mediaDir = File(File(out).parentFile, "media")
        YomitanReader(File(dir, "jitendex-yomitan.zip")).media { path, input ->
            val f = File(mediaDir, path)
            f.parentFile?.mkdirs()
            f.outputStream().use { input.copyTo(it) }
        }
        File(out).writeText(YomitanHtml.compose(theme, entries, kanji, null, mapOf(title to css),
                if (System.getenv("KAKU_COMPACT") != null) YomitanHtml.Options(compact = true, maxSenses = 3)
                else YomitanHtml.Options(back = System.getenv("KAKU_BACK"))) { _, path -> YomitanHtml.mediaUrl(mediaDir, path) }
                .replace("<script>", "<script>window.KakuHost={height(){}};"))
    }
}
