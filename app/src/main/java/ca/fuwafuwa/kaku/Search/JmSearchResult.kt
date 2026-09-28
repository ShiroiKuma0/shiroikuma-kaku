package ca.fuwafuwa.kaku.Search

import ca.fuwafuwa.kaku.Database.JmDictDatabase.Models.EntryOptimized
import ca.fuwafuwa.kaku.Deinflictor.DeinflectionInfo
import shiroikuma.kaku.dict.DictLookup

/**
 * One lookup result. [word] is the matched source text (the windows highlight that many
 * characters). A result from the imported Yomitan dictionaries carries [yomitan] (and the first
 * one also the looked-up character's [kanji]); [entry] is then an empty placeholder.
 */
data class JmSearchResult(
        val entry: EntryOptimized,
        val deinfInfo: DeinflectionInfo,
        val word: String,
        val yomitan: DictLookup.Entry? = null,
        val kanji: List<DictLookup.Kanji> = emptyList()
)
