package com.jono.audiobookwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Remembers the last book seen in each app (title, author, cover), so the
 * widget still shows it after the app closes, and which app is selected.
 */
object Store {
    data class Book(val title: String, val detail: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)

    fun selected(ctx: Context): String =
        prefs(ctx).getString("selected", null) ?: Sources.LIBBY.pkg

    fun select(ctx: Context, pkg: String) {
        prefs(ctx).edit().putString("selected", pkg).apply()
    }

    fun book(ctx: Context, pkg: String): Book? {
        val p = prefs(ctx)
        val title = p.getString("title_$pkg", null) ?: return null
        return Book(title, p.getString("detail_$pkg", null) ?: "")
    }

    /** Saves what a session is showing. Returns a signature so callers can skip redundant redraws. */
    fun capture(ctx: Context, c: MediaController): String {
        val pkg = c.packageName
        val playing = Media.isPlaying(c)
        val p = prefs(ctx)
        val edit = p.edit()
        val md = c.metadata

        if (md != null) {
            val title = md.text(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val album = md.text(MediaMetadata.METADATA_KEY_ALBUM)
            val author = md.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: md.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)

            // Audiobook apps usually put the book in "album" and the chapter in "title".
            val book = album ?: title
            if (book != null) {
                val chapter = title?.takeIf { it != book }
                edit.putString("title_$pkg", book)
                    .putString("detail_$pkg", listOfNotNull(author, chapter).joinToString(" · "))

                if (p.getString("art_key_$pkg", null) != book) {
                    val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    if (bmp != null) {
                        saveArt(ctx, pkg, bmp)
                        edit.putString("art_key_$pkg", book)
                    } else {
                        // New book, cover not sent yet: drop the old cover and retry next update.
                        artFile(ctx, pkg).delete()
                    }
                }
            }
        }
        if (playing) edit.putString("selected", pkg)
        edit.apply()

        return listOf(
            pkg, playing, p.getString("title_$pkg", ""), p.getString("detail_$pkg", ""),
            p.getString("art_key_$pkg", ""), p.getString("selected", ""),
        ).joinToString("|")
    }

    fun loadArt(ctx: Context, pkg: String): Bitmap? {
        val f = artFile(ctx, pkg)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    private fun artFile(ctx: Context, pkg: String) = File(ctx.filesDir, "art_$pkg.png")

    private fun saveArt(ctx: Context, pkg: String, src: Bitmap) {
        // Widget updates travel over a size-limited channel, so keep covers small (~60dp on screen).
        val maxSide = 200f
        val scale = maxSide / max(src.width, src.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else src
        try {
            FileOutputStream(artFile(ctx, pkg)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            artFile(ctx, pkg).delete()
        }
    }

    private fun MediaMetadata.text(key: String): String? = getString(key)?.takeIf { it.isNotBlank() }
}
