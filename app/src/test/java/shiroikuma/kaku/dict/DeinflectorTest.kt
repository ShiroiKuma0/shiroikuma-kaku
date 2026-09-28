package shiroikuma.kaku.dict

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The Kotlin port must deinflect exactly as Yomitan does: the reference is produced by Yomitan's
 * own LanguageTransformer (shiroikuma/yomitan/reference-deinflections.mjs), every result compared
 * as text | condition flags | transform chain, in order.
 */
class DeinflectorTest
{
    @Test
    fun matchesYomitan()
    {
        val deinflector = Deinflector(File("src/main/assets/yomitan/japanese-transforms.json").readText())
        val expected = JsonParser.parseString(javaClass.classLoader!!.getResource("deinflect-expected.json")!!.readText()).asJsonObject
        for ((word, results) in expected.entrySet())
        {
            val want = results.asJsonArray.map { it.asString }
            val got = deinflector.deinflect(word).map { "${it.text}|${it.conditions}|${it.trace.joinToString(",")}" }
            assertEquals("deinflections of $word", want, got)
        }
    }

    @Test
    fun findsDictionaryForms()
    {
        val deinflector = Deinflector(File("src/main/assets/yomitan/japanese-transforms.json").readText())
        val forms = deinflector.deinflect("食べさせられなかった").filter { deinflector.entryMatches(it, "v1") }.map { it.text }
        assert("食べる" in forms) { "食べる not among $forms" }
    }
}
