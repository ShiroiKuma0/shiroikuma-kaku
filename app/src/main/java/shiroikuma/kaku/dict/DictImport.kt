package shiroikuma.kaku.dict

import android.content.Context
import android.database.sqlite.SQLiteStatement
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Imports one Yomitan dictionary zip into [DictDb]: the picked file is spooled to the cache (a zip
 * needs random access, and SAF gives a stream), [YomitanReader] streams its banks, rows go in through
 * compiled statements in transactions of [BATCH], with the lookup indices dropped meanwhile and
 * rebuilt at the end. The dictionary row stays marked incomplete until everything is in, so an
 * import that dies half-way is removed at the next start ([DictDb.deleteIncomplete]) instead of
 * answering lookups with half a dictionary. A dictionary with the same title is replaced.
 */
object DictImport
{
    private const val BATCH = 5000

    /** What an import is doing, for the notification and the UI page. */
    data class Progress(val title: String, val bank: Int, val banks: Int, val rows: Int, val phase: String)

    fun interface Listener
    {
        fun onProgress(p: Progress)
    }

    class Result(val title: String, val revision: String, val terms: Int, val termMeta: Int, val kanji: Int,
                 val kanjiMeta: Int, val tags: Int)

    @Throws(IOException::class)
    fun import(context: Context, uri: Uri, listener: Listener?): Result
    {
        val app = context.applicationContext
        val spool = File(app.cacheDir, "dict-import-${System.nanoTime()}.zip")
        try
        {
            listener?.onProgress(Progress("", 0, 0, 0, "copy"))
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
            input.use { inp -> spool.outputStream().use { inp.copyTo(it, 256 * 1024) } }
            return importFile(app, spool, listener)
        }
        finally
        {
            spool.delete()
        }
    }

    @Throws(IOException::class)
    fun importFile(context: Context, zip: File, listener: Listener?): Result
    {
        val reader = YomitanReader(zip)
        val index = reader.index()
        val dictDb = DictDb.get(context)
        val db = dictDb.writableDatabase
        val id = dictDb.beginDictionary(index, reader.styles())

        val term = db.compileStatement("INSERT INTO terms (dict, expression, reading, def_tags, rules, score, glossary, sequence, term_tags) VALUES (?,?,?,?,?,?,?,?,?)")
        val termMeta = db.compileStatement("INSERT INTO term_meta (dict, expression, mode, data) VALUES (?,?,?,?)")
        val kanji = db.compileStatement("INSERT INTO kanji (dict, character, onyomi, kunyomi, tags, meanings, stats) VALUES (?,?,?,?,?,?,?)")
        val kanjiMeta = db.compileStatement("INSERT INTO kanji_meta (dict, character, mode, data) VALUES (?,?,?,?)")
        val tag = db.compileStatement("INSERT INTO tags (dict, name, category, ord, notes, score) VALUES (?,?,?,?,?,?)")

        var nTerms = 0
        var nTermMeta = 0
        var nKanji = 0
        var nKanjiMeta = 0
        var nTags = 0
        var inBatch = 0
        var bank = 0
        var banks = 0
        var ok = false

        dictDb.dropIndices(db)
        db.beginTransaction()
        try
        {
            fun step()
            {
                if (++inBatch >= BATCH)
                {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    db.beginTransaction()
                    inBatch = 0
                    listener?.onProgress(Progress(index.title, bank, banks, nTerms + nTermMeta + nKanji + nKanjiMeta + nTags, "rows"))
                }
            }

            fun SQLiteStatement.text(i: Int, s: String?) = if (s == null) bindNull(i) else bindString(i, s)

            reader.read(object : YomitanReader.Sink
            {
                override fun term(expression: String, reading: String, definitionTags: String, rules: String, score: Int,
                                  glossaryJson: String, sequence: Long, termTags: String)
                {
                    term.clearBindings()
                    term.bindLong(1, id); term.text(2, expression)
                    term.text(3, reading.ifEmpty { expression })
                    term.text(4, definitionTags); term.text(5, rules); term.bindLong(6, score.toLong())
                    term.bindBlob(7, DictDb.deflate(glossaryJson)); term.bindLong(8, sequence); term.text(9, termTags)
                    term.executeInsert()
                    nTerms++
                    step()
                }

                override fun termMeta(expression: String, mode: String, dataJson: String)
                {
                    termMeta.clearBindings()
                    termMeta.bindLong(1, id); termMeta.text(2, expression); termMeta.text(3, mode); termMeta.text(4, dataJson)
                    termMeta.executeInsert()
                    nTermMeta++
                    step()
                }

                override fun kanji(character: String, onyomi: String, kunyomi: String, tags: String, meaningsJson: String, statsJson: String)
                {
                    kanji.clearBindings()
                    kanji.bindLong(1, id); kanji.text(2, character); kanji.text(3, onyomi); kanji.text(4, kunyomi)
                    kanji.text(5, tags); kanji.text(6, meaningsJson); kanji.text(7, statsJson)
                    kanji.executeInsert()
                    nKanji++
                    step()
                }

                override fun kanjiMeta(character: String, mode: String, dataJson: String)
                {
                    kanjiMeta.clearBindings()
                    kanjiMeta.bindLong(1, id); kanjiMeta.text(2, character); kanjiMeta.text(3, mode); kanjiMeta.text(4, dataJson)
                    kanjiMeta.executeInsert()
                    nKanjiMeta++
                    step()
                }

                override fun tag(name: String, category: String, order: Int, notes: String, score: Int)
                {
                    tag.clearBindings()
                    tag.bindLong(1, id); tag.text(2, name); tag.text(3, category); tag.bindLong(4, order.toLong())
                    tag.text(5, notes); tag.bindLong(6, score.toLong())
                    tag.executeInsert()
                    nTags++
                    step()
                }

                override fun bank(done: Int, total: Int, name: String)
                {
                    bank = done
                    banks = total
                    listener?.onProgress(Progress(index.title, done, total, nTerms + nTermMeta + nKanji + nKanjiMeta + nTags, "rows"))
                }
            })
            db.setTransactionSuccessful()
            ok = true
        }
        finally
        {
            db.endTransaction()
            listener?.onProgress(Progress(index.title, banks, banks, nTerms + nTermMeta + nKanji + nKanjiMeta + nTags, "index"))
            dictDb.createIndices(db)
            for (s in listOf(term, termMeta, kanji, kanjiMeta, tag)) s.close()
            if (!ok) dictDb.delete(id)
        }
        dictDb.finishDictionary(id, nTerms, nTermMeta, nKanji, nKanjiMeta, nTags)
        return Result(index.title, index.revision, nTerms, nTermMeta, nKanji, nKanjiMeta, nTags)
    }
}
