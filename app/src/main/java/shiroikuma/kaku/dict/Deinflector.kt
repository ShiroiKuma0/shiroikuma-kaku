package shiroikuma.kaku.dict

import com.google.gson.JsonParser

/**
 * Yomitan's Japanese deinflector, ported: the engine of Yomitan's `language-transformer.js`
 * (GPL-3.0-or-later, Yomitan Authors) over the rules of its `ja/japanese-transforms.js`, exported
 * as data by `shiroikuma/yomitan/export-transforms.mjs` into `assets/yomitan/japanese-transforms.json`.
 *
 * [deinflect] is Yomitan's `transform`: a breadth-first walk from the text, applying every rule
 * whose conditions match the conditions reached so far (0 = the unconjugated start, which matches
 * anything), recording the chain of transforms; a rule already applied to the same text on the
 * path is a cycle and skipped. Each result carries the conditions it ended in, which a dictionary
 * entry's `rules` (parts of speech) must share for the result to count — [entryMatches].
 */
class Deinflector(json: String)
{
    /** One deinflected candidate: its text, the conditions it ended in, and the transforms applied (outermost first). */
    data class Result(val text: String, val conditions: Int, val trace: List<String>)

    private class Rule(val suffix: Boolean, val inflected: String, val deinflected: String, val conditionsIn: Int, val conditionsOut: Int)
    private class Transform(val id: String, val name: String, val rules: List<Rule>)
    private class Frame(val transform: String, val ruleIndex: Int, val text: String)

    private val conditionFlags = HashMap<String, Int>()
    private val partOfSpeechFlags = HashMap<String, Int>()
    private val transforms = ArrayList<Transform>()
    private val names = HashMap<String, String>()

    init
    {
        val root = JsonParser.parseString(json).asJsonObject
        val conditions = root.getAsJsonObject("conditions")

        // Flags as Yomitan assigns them: one bit per plain condition in declaration order, then
        // each condition with subConditions is the OR of its (resolved) subconditions.
        var next = 0
        var pending = conditions.entrySet().toList()
        while (pending.isNotEmpty())
        {
            val retry = ArrayList<Map.Entry<String, com.google.gson.JsonElement>>()
            for (entry in pending)
            {
                val c = entry.value.asJsonObject
                val sub = c.getAsJsonArray("subConditions")
                if (sub == null)
                {
                    require(next < 32) { "too many conditions" }
                    conditionFlags[entry.key] = 1 shl next++
                }
                else
                {
                    var flags = 0
                    var resolved = true
                    for (s in sub)
                    {
                        val f = conditionFlags[s.asString]
                        if (f == null) { resolved = false; break }
                        flags = flags or f
                    }
                    if (resolved) conditionFlags[entry.key] = flags else retry.add(entry)
                }
            }
            require(retry.size < pending.size) { "cycle in subConditions" }
            pending = retry
        }
        for ((type, c) in conditions.entrySet())
        {
            if (c.asJsonObject.get("isDictionaryForm")?.asBoolean == true) partOfSpeechFlags[type] = conditionFlags.getValue(type)
        }

        for (t in root.getAsJsonArray("transforms"))
        {
            val o = t.asJsonObject
            val rules = o.getAsJsonArray("rules").map { r ->
                val ro = r.asJsonObject
                Rule(ro.get("type").asString == "suffix", ro.get("inflected").asString, ro.get("deinflected").asString,
                        flagsOf(ro.getAsJsonArray("conditionsIn").map { it.asString }, strict = true),
                        flagsOf(ro.getAsJsonArray("conditionsOut").map { it.asString }, strict = true))
            }
            val id = o.get("id").asString
            transforms.add(Transform(id, o.get("name").asString, rules))
            names[id] = o.get("name").asString
        }
    }

    private fun flagsOf(types: List<String>, strict: Boolean): Int
    {
        var flags = 0
        for (t in types)
        {
            val f = conditionFlags[t]
            if (f == null) { require(!strict) { "unknown condition $t" }; continue }
            flags = flags or f
        }
        return flags
    }

    /** The condition flags a dictionary entry's `rules` field stands for (its parts of speech). */
    fun partsOfSpeechFlags(rules: String): Int
    {
        var flags = 0
        for (pos in rules.split(' ')) if (pos.isNotEmpty()) flags = flags or (partOfSpeechFlags[pos] ?: 0)
        return flags
    }

    /**
     * Whether a deinflection [result] can be the entry with parts of speech [entryRules]:
     * an un-deinflected text matches any entry; otherwise they must share a condition.
     */
    fun entryMatches(result: Result, entryRules: String): Boolean
    {
        if (result.conditions == 0) return true
        return (result.conditions and partsOfSpeechFlags(entryRules)) != 0
    }

    /** The user-facing name of a transform id (for "食べた ← past"). */
    fun nameOf(id: String): String = names[id] ?: id

    fun deinflect(source: String): List<Result>
    {
        val results = ArrayList<Result>()
        val traces = ArrayList<List<Frame>>()
        results.add(Result(source, 0, emptyList()))
        traces.add(emptyList())
        var i = 0
        while (i < results.size)
        {
            val cur = results[i]
            val trace = traces[i]
            for (transform in transforms)
            {
                for ((j, rule) in transform.rules.withIndex())
                {
                    if (cur.conditions != 0 && (cur.conditions and rule.conditionsIn) == 0) continue
                    val text = cur.text
                    val next = if (rule.suffix)
                    {
                        if (!text.endsWith(rule.inflected)) continue
                        text.substring(0, text.length - rule.inflected.length) + rule.deinflected
                    }
                    else
                    {
                        if (text != rule.inflected) continue
                        rule.deinflected
                    }
                    if (trace.any { it.transform == transform.id && it.ruleIndex == j && it.text == text }) continue
                    val newTrace = ArrayList<Frame>(trace.size + 1).apply { add(Frame(transform.id, j, text)); addAll(trace) }
                    results.add(Result(next, rule.conditionsOut, newTrace.map { it.transform }))
                    traces.add(newTrace)
                }
            }
            i++
        }
        return results
    }
}
