package hondana.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Hondana's own small database: saved words, character voices and cached
 * page readings. Kept apart from the Komikku database so upstream schema
 * migrations never collide with ours.
 */
class HondanaDatabase(context: Context) : SQLiteOpenHelper(context, "hondana.db", null, 1) {

    private val _vocabVersion = MutableStateFlow(0)

    /** Bumps whenever the word list changes, so screens can reload. */
    val vocabVersion: StateFlow<Int> = _vocabVersion.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE vocab(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                term TEXT NOT NULL,
                reading TEXT NOT NULL DEFAULT '',
                meaning TEXT NOT NULL DEFAULT '',
                sentence TEXT NOT NULL DEFAULT '',
                sentence_translation TEXT NOT NULL DEFAULT '',
                language TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT '',
                manga_id INTEGER,
                status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE voice_cast(
                manga_id INTEGER NOT NULL,
                character TEXT NOT NULL,
                voice TEXT,
                pitch REAL NOT NULL DEFAULT 1.0,
                rate REAL NOT NULL DEFAULT 1.0,
                voice_type TEXT NOT NULL DEFAULT 'other',
                note TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(manga_id, character)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE page_cache(
                cache_key TEXT PRIMARY KEY,
                json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // Words

    suspend fun addVocab(entry: VocabEntry): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("term", entry.term)
            put("reading", entry.reading)
            put("meaning", entry.meaning)
            put("sentence", entry.sentence)
            put("sentence_translation", entry.sentenceTranslation)
            put("language", entry.language)
            put("source", entry.source)
            if (entry.mangaId != null) put("manga_id", entry.mangaId) else putNull("manga_id")
            put("status", entry.status)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("vocab", null, values).also { _vocabVersion.value++ }
    }

    suspend fun vocab(): List<VocabEntry> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT _id, term, reading, meaning, sentence, sentence_translation, language, source, manga_id, " +
                "status, created_at FROM vocab ORDER BY created_at DESC",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        VocabEntry(
                            id = c.getLong(0),
                            term = c.getString(1),
                            reading = c.getString(2),
                            meaning = c.getString(3),
                            sentence = c.getString(4),
                            sentenceTranslation = c.getString(5),
                            language = c.getString(6),
                            source = c.getString(7),
                            mangaId = if (c.isNull(8)) null else c.getLong(8),
                            status = c.getInt(9),
                            createdAt = c.getLong(10),
                        ),
                    )
                }
            }
        }
    }

    suspend fun setVocabStatus(id: Long, status: Int) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put("status", status) }
        writableDatabase.update("vocab", values, "_id = ?", arrayOf(id.toString()))
        _vocabVersion.value++
    }

    suspend fun deleteVocab(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("vocab", "_id = ?", arrayOf(id.toString()))
        _vocabVersion.value++
    }

    // Character voices

    suspend fun cast(mangaId: Long): List<CastEntry> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT character, voice, pitch, rate, voice_type, note FROM voice_cast WHERE manga_id = ? " +
                "ORDER BY character COLLATE NOCASE",
            arrayOf(mangaId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CastEntry(
                            character = c.getString(0),
                            voice = if (c.isNull(1)) null else c.getString(1),
                            pitch = c.getFloat(2),
                            rate = c.getFloat(3),
                            voiceType = c.getString(4),
                            note = c.getString(5),
                        ),
                    )
                }
            }
        }
    }

    suspend fun saveCast(mangaId: Long, entry: CastEntry) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("manga_id", mangaId)
            put("character", entry.character)
            if (entry.voice != null) put("voice", entry.voice) else putNull("voice")
            put("pitch", entry.pitch)
            put("rate", entry.rate)
            put("voice_type", entry.voiceType)
            put("note", entry.note)
        }
        writableDatabase.insertWithOnConflict("voice_cast", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun deleteCast(mangaId: Long, character: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(
            "voice_cast",
            "manga_id = ? AND character = ?",
            arrayOf(mangaId.toString(), character),
        )
    }

    // Page readings

    suspend fun cachedPage(key: String): String? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT json FROM page_cache WHERE cache_key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    suspend fun cachePage(key: String, json: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("cache_key", key)
            put("json", json)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("page_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "DELETE FROM page_cache WHERE cache_key NOT IN " +
                "(SELECT cache_key FROM page_cache ORDER BY created_at DESC LIMIT $PAGE_CACHE_SIZE)",
        )
    }

    suspend fun clearPageCache() = withContext(Dispatchers.IO) {
        writableDatabase.delete("page_cache", null, null)
    }

    companion object {
        private const val PAGE_CACHE_SIZE = 600
    }
}

data class VocabEntry(
    val id: Long = 0,
    val term: String,
    val reading: String = "",
    val meaning: String = "",
    val sentence: String = "",
    val sentenceTranslation: String = "",
    val language: String = "",
    val source: String = "",
    val mangaId: Long? = null,
    /** 0 new, 1 learning, 2 known. */
    val status: Int = 0,
    val createdAt: Long = 0,
)

data class CastEntry(
    val character: String,
    /** Android TTS voice name; null means the engine's default voice for the language. */
    val voice: String?,
    val pitch: Float,
    val rate: Float,
    val voiceType: String = "other",
    val note: String = "",
)
