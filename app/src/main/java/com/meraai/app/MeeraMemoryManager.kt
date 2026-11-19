package com.meraai.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class MemoryRecord(
    val id: Long,
    val content: String,
    val category: String,
    val embedding: FloatArray?,
    val importance: Int,
    val updatedAt: Long
)

private class MeeraMemoryDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "meera_memory_v1.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role TEXT NOT NULL, " +
                "text TEXT NOT NULL, " +
                "time_label TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_created_at ON chat_messages(created_at)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS long_term_memories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "content TEXT NOT NULL UNIQUE, " +
                "category TEXT NOT NULL DEFAULT 'fact', " +
                "embedding_json TEXT NOT NULL DEFAULT '', " +
                "importance INTEGER NOT NULL DEFAULT 2, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS daily_summaries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "day_key TEXT NOT NULL UNIQUE, " +
                "summary TEXT NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }
}

object MeeraMemoryManager {
    @Volatile private var dbHelper: MeeraMemoryDbHelper? = null
    @Volatile private var migrated = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun db(ctx: Context): SQLiteDatabase {
        val helper = dbHelper ?: synchronized(this) {
            dbHelper ?: MeeraMemoryDbHelper(ctx.applicationContext).also { dbHelper = it }
        }
        return helper.writableDatabase
    }

    // ==================== 1. PERSISTENT CHAT HISTORY (7 / 15 / 30 DAYS) ====================

    fun saveChatMessage(ctx: Context, role: String, text: String, timeLabel: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        try {
            val cv = ContentValues().apply {
                put("role", role)
                put("text", clean)
                put("time_label", timeLabel)
                put("created_at", System.currentTimeMillis())
            }
            db(ctx).insert("chat_messages", null, cv)
        } catch (e: Exception) {
            MeeraDebugLog.log("MEMORY_DB", "saveChatMessage error: ${e.message}")
        }
    }

    fun pruneOldChats(ctx: Context, retentionDays: Int = Prefs(ctx).chatRetentionDays): Int {
        val days = retentionDays.coerceIn(7, 30)
        val cutoffMs = System.currentTimeMillis() - (days.toLong() * 24L * 60L * 60L * 1000L)
        return try {
            db(ctx).delete("chat_messages", "created_at < ?", arrayOf(cutoffMs.toString()))
        } catch (_: Exception) {
            0
        }
    }

    fun loadRecentChats(ctx: Context, limit: Int = 200): List<ChatMessage> {
        pruneOldChats(ctx)
        val out = ArrayList<ChatMessage>()
        try {
            val c = db(ctx).rawQuery(
                "SELECT role, text, time_label FROM chat_messages ORDER BY created_at DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            c.use {
                while (it.moveToNext()) {
                    out.add(ChatMessage(it.getString(0), it.getString(1), it.getString(2)))
                }
            }
        } catch (e: Exception) {
            MeeraDebugLog.log("MEMORY_DB", "loadRecentChats error: ${e.message}")
        }
        return out.reversed()
    }

    fun clearAllChats(ctx: Context) {
        try {
            db(ctx).delete("chat_messages", null, null)
        } catch (_: Exception) {}
    }

    // ==================== 2. LONG-TERM HYBRID MEMORY (VECTOR + KEYWORD) ====================

    fun migrateLegacyIfNeeded(ctx: Context, legacyFacts: List<String>) {
        if (migrated || legacyFacts.isEmpty()) return
        migrated = true
        val now = System.currentTimeMillis()
        try {
            val database = db(ctx)
            for (fact in legacyFacts) {
                val clean = fact.trim()
                if (clean.length < 4) continue
                val cat = if (clean.startsWith("सीखा हुआ स्किल")) "skill" else "fact"
                val cv = ContentValues().apply {
                    put("content", clean)
                    put("category", cat)
                    put("embedding_json", "")
                    put("importance", if (cat == "skill") 3 else 2)
                    put("created_at", now)
                    put("updated_at", now)
                }
                database.insertWithOnConflict("long_term_memories", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
        } catch (_: Exception) {}
    }

    fun upsertMemory(
        ctx: Context,
        content: String,
        category: String = "fact",
        importance: Int = 2,
        fetchEmbeddingAsync: Boolean = true
    ) {
        val clean = content.trim()
        if (clean.length < 4) return
        val now = System.currentTimeMillis()
        try {
            val database = db(ctx)
            val existing = getAllMemoryRecords(ctx).firstOrNull { it.content.equals(clean, ignoreCase = true) }
            if (existing != null) {
                val cv = ContentValues().apply {
                    put("updated_at", now)
                    put("importance", maxOf(existing.importance, importance))
                }
                database.update("long_term_memories", cv, "id = ?", arrayOf(existing.id.toString()))
                return
            }
            val cv = ContentValues().apply {
                put("content", clean)
                put("category", category)
                put("embedding_json", "")
                put("importance", importance.coerceIn(1, 5))
                put("created_at", now)
                put("updated_at", now)
            }
            val rowId = database.insertWithOnConflict("long_term_memories", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            if (fetchEmbeddingAsync && rowId > 0) {
                val apiKey = Prefs(ctx).apiKey
                if (apiKey.isNotBlank()) {
                    Thread {
                        val vec = fetchGeminiEmbedding(apiKey, clean)
                        if (vec != null) {
                            saveEmbeddingForRow(ctx, rowId, vec)
                        }
                    }.start()
                }
            }
        } catch (e: Exception) {
            MeeraDebugLog.log("MEMORY_DB", "upsertMemory error: ${e.message}")
        }
    }

    fun deleteMemory(ctx: Context, content: String) {
        try {
            db(ctx).delete("long_term_memories", "LOWER(content) = LOWER(?)", arrayOf(content.trim()))
        } catch (_: Exception) {}
    }

    fun updateMemory(ctx: Context, oldContent: String, newContent: String) {
        val clean = newContent.trim()
        if (clean.length < 4) return
        try {
            val cv = ContentValues().apply {
                put("content", clean)
                put("embedding_json", "")
                put("updated_at", System.currentTimeMillis())
            }
            val updated = db(ctx).update("long_term_memories", cv, "LOWER(content) = LOWER(?)", arrayOf(oldContent.trim()))
            if (updated == 0) upsertMemory(ctx, clean)
        } catch (_: Exception) {}
    }

    fun clearAllMemories(ctx: Context) {
        try {
            db(ctx).delete("long_term_memories", null, null)
            db(ctx).delete("daily_summaries", null, null)
        } catch (_: Exception) {}
    }

    fun getAllMemoryStrings(ctx: Context): List<String> =
        getAllMemoryRecords(ctx).map { it.content }

    private fun getAllMemoryRecords(ctx: Context): List<MemoryRecord> {
        val out = ArrayList<MemoryRecord>()
        try {
            val c = db(ctx).rawQuery(
                "SELECT id, content, category, embedding_json, importance, updated_at FROM long_term_memories ORDER BY updated_at ASC",
                null
            )
            c.use {
                while (it.moveToNext()) {
                    val embRaw = it.getString(3).orEmpty()
                    out.add(
                        MemoryRecord(
                            id = it.getLong(0),
                            content = it.getString(1),
                            category = it.getString(2),
                            embedding = parseEmbeddingJson(embRaw),
                            importance = it.getInt(4),
                            updatedAt = it.getLong(5)
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun hybridSearch(ctx: Context, query: String, topK: Int = 8): List<String> {
        val all = getAllMemoryRecords(ctx)
        if (all.size <= topK) return all.map { it.content }

        val qTokens = tokenize(query)
        val apiKey = Prefs(ctx).apiKey
        val hasAnyEmbeddings = all.any { it.embedding != null }
        val queryVec = if (hasAnyEmbeddings && apiKey.isNotBlank() && query.trim().length >= 3) {
            fetchGeminiEmbedding(apiKey, query)
        } else null

        val scored = all.map { rec ->
            val recTokens = tokenize(rec.content)
            val kwScore = if (qTokens.isEmpty() || recTokens.isEmpty()) 0f else {
                val overlap = qTokens.count { qt -> recTokens.any { rt -> rt == qt || (qt.length >= 4 && rt.contains(qt)) } }
                overlap.toFloat() / qTokens.size.toFloat()
            }
            val vecScore = if (queryVec != null && rec.embedding != null) {
                cosineSimilarity(queryVec, rec.embedding).coerceAtLeast(0f)
            } else 0f
            val impBoost = (rec.importance.coerceIn(1, 5) * 0.04f)
            val finalScore = if (queryVec != null && rec.embedding != null) {
                (vecScore * 0.58f) + (kwScore * 0.38f) + impBoost
            } else {
                (kwScore * 0.85f) + impBoost
            }
            rec.content to finalScore
        }

        return scored.sortedByDescending { it.second }.take(topK).map { it.first }
    }

    // ==================== 3. DAILY EPISODIC SUMMARIES ====================

    fun saveDailySummary(ctx: Context, summary: String, dayKey: String = todayKey()) {
        val clean = summary.trim()
        if (clean.length < 8) return
        try {
            val cv = ContentValues().apply {
                put("day_key", dayKey)
                put("summary", clean)
                put("updated_at", System.currentTimeMillis())
            }
            db(ctx).insertWithOnConflict("daily_summaries", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Exception) {}
    }

    fun getRecentSummaries(ctx: Context, limit: Int = 3): List<String> {
        val out = ArrayList<String>()
        try {
            val c = db(ctx).rawQuery(
                "SELECT day_key, summary FROM daily_summaries ORDER BY day_key DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            c.use {
                while (it.moveToNext()) {
                    out.add("[${it.getString(0)}]: ${it.getString(1)}")
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun saveEmbeddingForRow(ctx: Context, rowId: Long, vec: FloatArray) {
        try {
            val arr = JSONArray()
            for (v in vec) arr.put(v.toDouble())
            val cv = ContentValues().apply { put("embedding_json", arr.toString()) }
            db(ctx).update("long_term_memories", cv, "id = ?", arrayOf(rowId.toString()))
        } catch (_: Exception) {}
    }

    private fun fetchGeminiEmbedding(apiKey: String, text: String): FloatArray? {
        val models = listOf("text-embedding-004", "gemini-embedding-001")
        for (m in models) {
            try {
                val body = JSONObject().put(
                    "content", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", text.take(800)))
                    )
                )
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$m:embedContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val values = JSONObject(resp.body!!.string())
                            .optJSONObject("embedding")
                            ?.optJSONArray("values")
                        if (values != null && values.length() > 0) {
                            val out = FloatArray(values.length())
                            for (i in 0 until values.length()) out[i] = values.optDouble(i, 0.0).toFloat()
                            return out
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseEmbeddingJson(raw: String): FloatArray? {
        if (raw.length < 4 || !raw.startsWith("[")) return null
        return try {
            val arr = JSONArray(raw)
            val out = FloatArray(arr.length())
            for (i in 0 until arr.length()) out[i] = arr.optDouble(i, 0.0).toFloat()
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .filter { it.length >= 2 }
            .toSet()

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 0f) dot / denom else 0f
    }
}
