package ca.fuwafuwa.kaku.Search

import android.content.Context
import android.os.AsyncTask
import ca.fuwafuwa.kaku.Database.JmDictDatabase.Models.EntryOptimized
import ca.fuwafuwa.kaku.Deinflictor.DeinflectionInfo
import ca.fuwafuwa.kaku.LangUtils
import shiroikuma.kaku.dict.DictLookup

/** The Yomitan-dictionary twin of [JmTask]: the same callback, results carrying [DictLookup.Entry]. */
@Suppress("DEPRECATION")
class YomitanTask(private val searchInfo: SearchInfo, private val done: JmTask.SearchJmTaskDone, context: Context)
    : AsyncTask<Void, Void, List<JmSearchResult>>()
{
    private val lookup = DictLookup(context)

    override fun doInBackground(vararg params: Void): List<JmSearchResult> =
            results(lookup, searchInfo.text.substring(searchInfo.textOffset))

    override fun onPostExecute(result: List<JmSearchResult>)
    {
        done.jmTaskCallback(result, searchInfo)
    }

    companion object
    {
        /** The results for [text] scanned from its start — also for a followed cross-reference. */
        fun results(lookup: DictLookup, text: String): List<JmSearchResult>
        {
        if (text.isEmpty()) return emptyList()
        val entries = lookup.lookup(text)
        val first = String(intArrayOf(text.codePointAt(0)), 0, 1)
        val kanji = if (LangUtils.IsKanji(first[0])) lookup.kanji(first) else emptyList()
        if (entries.isEmpty() && kanji.isEmpty()) return emptyList()
        val list = entries.mapIndexed { i, e ->
            JmSearchResult(EntryOptimized(), DeinflectionInfo(e.expression, 0, e.deinflection.joinToString(" · ")),
                    e.sourceText, e, if (i == 0) kanji else emptyList())
        }
        return list.ifEmpty {
            listOf(JmSearchResult(EntryOptimized(), DeinflectionInfo(first, 0, ""), first, null, kanji))
        }
        }
    }
}
