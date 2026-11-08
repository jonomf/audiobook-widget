package com.jono.audiobookwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min

/**
 * Remembers recent books across both apps (title, chapter, author, cover), so the widget
 * can show the last book after an app closes and list other recent books when there's room.
 */
object Store {
    private const val MAX_RECENTS = 8

    /** One book. [key] is app + title; the first entry per app is that app's current book. */
    data class Book(
        val key: String,
        val pkg: String,
        val title: String,
        val chapter: String?,
        val author: String?,
        val lastPlayed: Long,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)

    fun selected(ctx: Context): String =
        prefs(ctx).getString("selected", null) ?: Sources.LIBBY.pkg

    fun select(ctx: Context, pkg: String) {
        prefs(ctx).edit().putString("selected", pkg).apply()
    }

    /** Most recent first. */
    fun recents(ctx: Context): List<Book> {
        val raw = prefs(ctx).getString("recents", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Book(
                    key = o.getString("key"),
                    pkg = o.getString("pkg"),
                    title = o.getString("title"),
                    chapter = o.optString("chapter").takeIf { it.isNotBlank() },
                    author = o.optString("author").takeIf { it.isNotBlank() },
                    lastPlayed = o.optLong("lastPlayed"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The app's current (most recently played) book. */
    fun current(ctx: Context, pkg: String): Book? = recents(ctx).firstOrNull { it.pkg == pkg }

    /** Saves what a session is showing. Returns a signature so callers can skip redundant redraws. */
    fun capture(ctx: Context, c: MediaController): String {
        val pkg = c.packageName
        val playing = Media.isPlaying(c)
        val md = c.metadata
        var key = ""

        if (md != null) {
            val title = md.text(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val album = md.text(MediaMetadata.METADATA_KEY_ALBUM)
            val author = md.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: md.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)

            // Audiobook apps usually put the book in "album" and the chapter in "title".
            val bookTitle = album ?: title
            if (bookTitle != null) {
                key = "$pkg|$bookTitle"
                val chapter = title?.takeIf { it != bookTitle }?.let { shortenChapter(it, bookTitle) }
                val list = recents(ctx).toMutableList()
                val old = list.firstOrNull { it.key == key }
                list.removeAll { it.key == key }
                val isNew = old == null
                val book = Book(
                    key = key,
                    pkg = pkg,
                    title = bookTitle,
                    chapter = chapter ?: old?.chapter,
                    author = author ?: old?.author,
                    lastPlayed = if (playing || isNew) System.currentTimeMillis() else old!!.lastPlayed,
                )
                list.add(book)
                list.sortByDescending { it.lastPlayed }
                saveRecents(ctx, list.take(MAX_RECENTS))

                if (!coverFile(ctx, coverId(key)).exists()) {
                    val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    if (bmp != null) saveArt(coverFile(ctx, coverId(key)), bmp)
                }
            }
        }
        if (playing) select(ctx, pkg)

        // Redraw when what's shown changes, including seeks and speed changes (time left),
        // but not on routine position ticks.
        val remaining = Media.remainingMs(c)
        val endBucket = remaining?.let { if (playing) (SystemClock.elapsedRealtime() + it) / 2000 else it / 2000 }
        val b = current(ctx, pkg)
        return listOf(
            pkg, playing, key, b?.chapter, b?.author, coverFile(ctx, coverId(key)).exists(),
            selected(ctx), endBucket, Media.speed(c), Media.actions(c),
        ).joinToString("|")
    }

    // --- Covers: one rounded PNG per book, served to the launcher by ArtProvider. ---

    fun coverId(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    fun coverFile(ctx: Context, id: String) = File(ctx.filesDir, "cover_$id.png")

    /** Content URI for a book's cover; the version changes whenever the file does, so the launcher reloads it. */
    fun coverUri(ctx: Context, book: Book): Uri? {
        val id = coverId(book.key)
        val f = coverFile(ctx, id)
        if (!f.exists()) return null
        return Uri.parse("content://${ctx.packageName}.art/$id/${f.lastModified()}")
    }

    private fun saveRecents(ctx: Context, list: List<Book>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().apply {
                put("key", b.key)
                put("pkg", b.pkg)
                put("title", b.title)
                put("chapter", b.chapter ?: "")
                put("author", b.author ?: "")
                put("lastPlayed", b.lastPlayed)
            })
        }
        prefs(ctx).edit().putString("recents", arr.toString()).apply()
        // Drop covers of books that fell off the list.
        val keep = list.map { "cover_${coverId(it.key)}.png" }.toSet()
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("cover_") && it.name !in keep }?.forEach { it.delete() }
    }

    /** Square-crops, scales and rounds the cover once, when it arrives. */
    private fun saveArt(file: File, src: Bitmap) {
        val side = min(src.width, src.height)
        val outSide = min(side, 512)
        val scale = outSide.toFloat() / side
        val out = Bitmap.createBitmap(outSide, outSide, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setTranslate(-(src.width - side) / 2f, -(src.height - side) / 2f)
                postScale(scale, scale)
            })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val r = outSide * 0.08f
        Canvas(out).drawRoundRect(RectF(0f, 0f, outSide.toFloat(), outSide.toFloat()), r, r, paint)
        try {
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            file.delete()
        }
    }

    /** "My Book - Chapter 2: The Storm" → "Ch. 2: The Storm", so the part that changes fits. */
    private fun shortenChapter(raw: String, book: String): String {
        var s = raw.trim()
        if (s.length > book.length && s.startsWith(book, ignoreCase = true)) {
            s = s.substring(book.length).trimStart(' ', '-', '–', '—', ':', '|', ',', '.')
        }
        s = s.replace(Regex("^chapter\\s+", RegexOption.IGNORE_CASE), "Ch. ")
        return s.ifBlank { raw.trim() }
    }

    private fun MediaMetadata.text(key: String): String? = getString(key)?.takeIf { it.isNotBlank() }
}
