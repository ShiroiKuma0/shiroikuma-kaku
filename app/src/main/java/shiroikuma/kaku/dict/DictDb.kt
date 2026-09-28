package shiroikuma.kaku.dict

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The imported Yomitan dictionaries — one SQLite file, `files/yomitan.db`, in the shape of memento's
 * dictionary database (白い熊's desktop reader): a row per dictionary, then its terms, term meta
 * (frequency / pitch), kanji, kanji meta and tags, each keyed by the dictionary's id.
 * Glossaries are stored deflated (JSON), which keeps JMdict well under half its raw size.
 *
 * It lives beside the old bundled database in `files/`, so the Export / Import "dictionary data"
 * category (every `.db` file in `files`) carries it to a new phone without anything imported by hand.
 */
class DictDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION)
{
    private val app: Context = context.applicationContext

    data class Dictionary(val id: Long, val title: String, val revision: String, val format: Int,
                          val enabled: Boolean, val priority: Int, val terms: Int, val termMeta: Int, val kanji: Int,
                          val kanjiMeta: Int, val attribution: String?, val downloadUrl: String?, val styles: String?,
                          val complete: Boolean)

    data class TermRow(val dict: Long, val expression: String, val reading: String, val definitionTags: String,
                       val rules: String, val score: Int, val glossaryJson: String, val sequence: Long, val termTags: String)

    data class MetaRow(val dict: Long, val key: String, val mode: String, val dataJson: String)

    data class KanjiRow(val dict: Long, val character: String, val onyomi: String, val kunyomi: String, val tags: String,
                        val meaningsJson: String, val statsJson: String)

    data class TagRow(val dict: Long, val name: String, val category: String, val order: Int, val notes: String, val score: Int)

    override fun onCreate(db: SQLiteDatabase)
    {
        db.execSQL("""CREATE TABLE dictionaries (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL UNIQUE,
            revision TEXT, format INTEGER, sequenced INTEGER, author TEXT, url TEXT, description TEXT, attribution TEXT,
            source_language TEXT, target_language TEXT, frequency_mode TEXT, is_updatable INTEGER, index_url TEXT,
            download_url TEXT, styles TEXT, enabled INTEGER NOT NULL DEFAULT 1, priority INTEGER NOT NULL DEFAULT 0,
            terms INTEGER DEFAULT 0, term_meta INTEGER DEFAULT 0, kanji INTEGER DEFAULT 0, kanji_meta INTEGER DEFAULT 0,
            tags INTEGER DEFAULT 0, complete INTEGER NOT NULL DEFAULT 0, imported_at INTEGER)""")
        db.execSQL("""CREATE TABLE terms (dict INTEGER NOT NULL, expression TEXT NOT NULL, reading TEXT NOT NULL,
            def_tags TEXT, rules TEXT, score INTEGER, glossary BLOB, sequence INTEGER, term_tags TEXT)""")
        db.execSQL("CREATE TABLE term_meta (dict INTEGER NOT NULL, expression TEXT NOT NULL, mode TEXT, data TEXT)")
        db.execSQL("""CREATE TABLE kanji (dict INTEGER NOT NULL, character TEXT NOT NULL, onyomi TEXT, kunyomi TEXT,
            tags TEXT, meanings TEXT, stats TEXT)""")
        db.execSQL("CREATE TABLE kanji_meta (dict INTEGER NOT NULL, character TEXT NOT NULL, mode TEXT, data TEXT)")
        db.execSQL("CREATE TABLE tags (dict INTEGER NOT NULL, name TEXT, category TEXT, ord INTEGER, notes TEXT, score INTEGER)")
        createIndices(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int)
    {
        // Version 1 is the first schema; later versions migrate here, never by dropping data.
    }

    override fun onConfigure(db: SQLiteDatabase)
    {
        db.setForeignKeyConstraintsEnabled(false)
    }

    // ---- import --------------------------------------------------------------------------------

    /** Drop the lookup indices before a bulk import (rows go in several times faster)… */
    fun dropIndices(db: SQLiteDatabase)
    {
        for (i in INDICES.keys) db.execSQL("DROP INDEX IF EXISTS $i")
    }

    /** …and build them again afterwards. */
    fun createIndices(db: SQLiteDatabase)
    {
        for ((name, def) in INDICES) db.execSQL("CREATE INDEX IF NOT EXISTS $name ON $def")
    }

    /** A new dictionary row, marked incomplete until [finishDictionary]; replaces one of the same title. */
    fun beginDictionary(index: YomitanReader.Index, styles: String?): Long
    {
        val db = writableDatabase
        // The same dictionary: the same title, or — for an updatable one whose title carries its
        // date ("JMdict [2026-09-28]") — the same indexUrl, as Yomitan itself matches updates.
        findByTitle(index.title)?.let { delete(it) }
        index.indexUrl?.takeIf { it.isNotEmpty() }?.let { url ->
            db.rawQuery("SELECT id FROM dictionaries WHERE index_url = ?", arrayOf(url)).use { c ->
                val ids = ArrayList<Long>()
                while (c.moveToNext()) ids.add(c.getLong(0))
                ids
            }.forEach { delete(it) }
        }
        val v = ContentValues().apply {
            put("title", index.title); put("revision", index.revision); put("format", index.format)
            put("sequenced", if (index.sequenced) 1 else 0); put("author", index.author); put("url", index.url)
            put("description", index.description); put("attribution", index.attribution)
            put("source_language", index.sourceLanguage); put("target_language", index.targetLanguage)
            put("frequency_mode", index.frequencyMode); put("is_updatable", if (index.isUpdatable) 1 else 0)
            put("index_url", index.indexUrl); put("download_url", index.downloadUrl); put("styles", styles)
            put("priority", nextPriority()); put("complete", 0); put("imported_at", System.currentTimeMillis())
        }
        return db.insertOrThrow("dictionaries", null, v)
    }

    fun finishDictionary(id: Long, terms: Int, termMeta: Int, kanji: Int, kanjiMeta: Int, tags: Int)
    {
        val v = ContentValues().apply {
            put("terms", terms); put("term_meta", termMeta); put("kanji", kanji); put("kanji_meta", kanjiMeta)
            put("tags", tags); put("complete", 1)
        }
        writableDatabase.update("dictionaries", v, "id = ?", arrayOf(id.toString()))
    }

    /** Remove a dictionary and every row of it. */
    fun delete(id: Long)
    {
        val db = writableDatabase
        db.beginTransaction()
        try
        {
            for (t in listOf("terms", "term_meta", "kanji", "kanji_meta", "tags")) db.delete(t, "dict = ?", arrayOf(id.toString()))
            db.delete("dictionaries", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        }
        finally
        {
            db.endTransaction()
        }
        mediaDir(app, id).deleteRecursively()
    }

    /**
     * Dictionaries an interrupted import left half-written: removed at start-up — and media
     * folders no dictionary owns any more.
     */
    fun deleteIncomplete()
    {
        for (d in dictionaries()) if (!d.complete) delete(d.id)
        val ids = dictionaries().map { it.id.toString() }.toSet()
        mediaRoot(app).listFiles()?.forEach { if (it.name !in ids) it.deleteRecursively() }
    }

    private fun nextPriority(): Int =
            readableDatabase.rawQuery("SELECT COALESCE(MAX(priority), -1) + 1 FROM dictionaries", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }

    // ---- management ------------------------------------------------------------------------------

    fun dictionaries(): List<Dictionary>
    {
        val out = ArrayList<Dictionary>()
        readableDatabase.rawQuery("""SELECT id, title, revision, format, enabled, priority, terms, term_meta, kanji,
                kanji_meta, attribution, download_url, styles, complete FROM dictionaries ORDER BY priority, id""", null).use { c ->
            while (c.moveToNext())
            {
                out.add(Dictionary(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getInt(3), c.getInt(4) != 0,
                        c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9), c.getString(10), c.getString(11),
                        c.getString(12), c.getInt(13) != 0))
            }
        }
        return out
    }

    fun findByTitle(title: String): Long? =
            readableDatabase.rawQuery("SELECT id FROM dictionaries WHERE title = ?", arrayOf(title)).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }

    fun setEnabled(id: Long, enabled: Boolean)
    {
        writableDatabase.update("dictionaries", ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
                "id = ?", arrayOf(id.toString()))
    }

    /** Move a dictionary one place up (-1) or down (+1) in the display order. */
    fun move(id: Long, delta: Int)
    {
        val list = dictionaries().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        val moved = list.removeAt(i)
        list.add(j, moved)
        val db = writableDatabase
        db.beginTransaction()
        try
        {
            for ((p, d) in list.withIndex())
            {
                db.update("dictionaries", ContentValues().apply { put("priority", p) }, "id = ?", arrayOf(d.id.toString()))
            }
            db.setTransactionSuccessful()
        }
        finally
        {
            db.endTransaction()
        }
    }

    /** Whether any complete dictionary with terms is switched on — the Yomitan lookup is then used. */
    fun hasEnabledTerms(): Boolean =
            readableDatabase.rawQuery("SELECT 1 FROM dictionaries WHERE enabled = 1 AND complete = 1 AND terms > 0 LIMIT 1", null)
                    .use { it.moveToFirst() }

    // ---- lookup ----------------------------------------------------------------------------------

    /** Terms whose expression or reading is one of [keys], from enabled dictionaries. */
    fun termsFor(keys: Collection<String>): List<TermRow>
    {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<TermRow>()
        for (chunk in keys.distinct().chunked(400))
        {
            val marks = chunk.joinToString(",") { "?" }
            val args = (chunk + chunk).toTypedArray()
            readableDatabase.rawQuery("""SELECT t.dict, t.expression, t.reading, t.def_tags, t.rules, t.score, t.glossary,
                    t.sequence, t.term_tags FROM terms t JOIN dictionaries d ON d.id = t.dict
                    WHERE d.enabled = 1 AND d.complete = 1 AND (t.expression IN ($marks) OR t.reading IN ($marks))""", args).use { c ->
                while (c.moveToNext())
                {
                    out.add(TermRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3) ?: "",
                            c.getString(4) ?: "", c.getInt(5), inflate(c.getBlob(6)), c.getLong(7), c.getString(8) ?: ""))
                }
            }
        }
        return out
    }

    /** Frequency / pitch / IPA rows for [keys] (term expressions), from enabled dictionaries. */
    fun termMetaFor(keys: Collection<String>): List<MetaRow>
    {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<MetaRow>()
        for (chunk in keys.distinct().chunked(800))
        {
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("""SELECT m.dict, m.expression, m.mode, m.data FROM term_meta m
                    JOIN dictionaries d ON d.id = m.dict WHERE d.enabled = 1 AND d.complete = 1 AND m.expression IN ($marks)""",
                    chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out.add(MetaRow(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: ""))
            }
        }
        return out
    }

    fun kanjiFor(character: String): List<KanjiRow>
    {
        val out = ArrayList<KanjiRow>()
        readableDatabase.rawQuery("""SELECT k.dict, k.character, k.onyomi, k.kunyomi, k.tags, k.meanings, k.stats FROM kanji k
                JOIN dictionaries d ON d.id = k.dict WHERE d.enabled = 1 AND d.complete = 1 AND k.character = ?
                ORDER BY d.priority""", arrayOf(character)).use { c ->
            while (c.moveToNext())
            {
                out.add(KanjiRow(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "",
                        c.getString(4) ?: "", c.getString(5) ?: "[]", c.getString(6) ?: "{}"))
            }
        }
        return out
    }

    fun kanjiMetaFor(character: String): List<MetaRow>
    {
        val out = ArrayList<MetaRow>()
        readableDatabase.rawQuery("""SELECT m.dict, m.character, m.mode, m.data FROM kanji_meta m
                JOIN dictionaries d ON d.id = m.dict WHERE d.enabled = 1 AND d.complete = 1 AND m.character = ?""",
                arrayOf(character)).use { c ->
            while (c.moveToNext()) out.add(MetaRow(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: ""))
        }
        return out
    }

    /** Tags of the enabled dictionaries, by dictionary id then name. */
    fun tags(): Map<Long, Map<String, TagRow>>
    {
        val out = HashMap<Long, HashMap<String, TagRow>>()
        readableDatabase.rawQuery("""SELECT t.dict, t.name, t.category, t.ord, t.notes, t.score FROM tags t
                JOIN dictionaries d ON d.id = t.dict WHERE d.enabled = 1""", null).use { c ->
            while (c.moveToNext())
            {
                val r = TagRow(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getString(4) ?: "", c.getInt(5))
                out.getOrPut(r.dict) { HashMap() }[r.name] = r
            }
        }
        return out
    }

    companion object
    {
        const val NAME = "yomitan.db"
        const val MEDIA_DIR = "yomitan-media"

        /** Every dictionary's images, a folder per dictionary id: `files/yomitan-media/<id>/<zip path>`. */
        fun mediaRoot(context: Context) = java.io.File(context.applicationContext.filesDir, MEDIA_DIR)

        fun mediaDir(context: Context, id: Long) = java.io.File(mediaRoot(context), id.toString())
        private const val VERSION = 1

        private val INDICES = linkedMapOf(
                "terms_expression" to "terms(expression)",
                "terms_reading" to "terms(reading)",
                "term_meta_expression" to "term_meta(expression)",
                "kanji_character" to "kanji(character)",
                "kanji_meta_character" to "kanji_meta(character)",
                "tags_dict" to "tags(dict)")

        @Volatile private var instance: DictDb? = null

        @JvmStatic
        fun get(context: Context): DictDb = instance ?: synchronized(this) {
            instance ?: DictDb(context).also { instance = it }
        }

        /** Close the open database (before an Export / Import replaces the file). */
        @JvmStatic
        fun closeInstance()
        {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        fun deflate(text: String): ByteArray
        {
            val d = Deflater(6)
            d.setInput(text.toByteArray(Charsets.UTF_8))
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        fun inflate(bytes: ByteArray?): String
        {
            if (bytes == null || bytes.isEmpty()) return "[]"
            val i = Inflater()
            i.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!i.finished())
            {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) break
                out.write(buf, 0, n)
            }
            i.end()
            return out.toString("UTF-8")
        }
    }
}
