package com.jono.audiobookwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The widget's own bookmarks: book, chapter, position and when. Kept on the phone in this app.
 * When Libby or Audible offers a bookmark action through its media session, the widget also
 * triggers that, so the bookmark lands in the app too ([Mark.inApp]).
 */
object Bookmarks {
    private const val MAX = 100

    data class Mark(
        val bookKey: String,
        val chapter: String?,
        val positionMs: Long,
        val createdAt: Long,
        val inApp: Boolean,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Mark> {
        val raw = prefs(ctx).getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Mark(
                    bookKey = o.getString("book"),
                    chapter = o.optString("chapter").takeIf { it.isNotBlank() },
                    positionMs = o.getLong("pos"),
                    createdAt = o.getLong("at"),
                    inApp = o.optBoolean("inApp"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Newest first. */
    fun forBook(ctx: Context, bookKey: String): List<Mark> =
        all(ctx).filter { it.bookKey == bookKey }.sortedByDescending { it.createdAt }

    fun find(ctx: Context, createdAt: Long): Mark? = all(ctx).firstOrNull { it.createdAt == createdAt }

    fun add(ctx: Context, mark: Mark) {
        val list = (all(ctx) + mark).sortedByDescending { it.createdAt }.take(MAX)
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("book", m.bookKey)
                put("chapter", m.chapter ?: "")
                put("pos", m.positionMs)
                put("at", m.createdAt)
                put("inApp", m.inApp)
            })
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    /** 754000 → "12:34", 4520000 → "1:15:20". */
    fun formatPosition(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
