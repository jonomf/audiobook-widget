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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

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

                if (p.getString("cover_key_$pkg", null) != book) {
                    val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    if (bmp != null) {
                        saveArt(ctx, pkg, bmp)
                        edit.putString("cover_key_$pkg", book)
                    } else {
                        // New book, cover not sent yet: drop the old cover and retry next update.
                        coverFile(ctx, pkg).delete()
                    }
                }
            }
        }
        if (playing) edit.putString("selected", pkg)
        edit.apply()

        return listOf(
            pkg, playing, p.getString("title_$pkg", ""), p.getString("detail_$pkg", ""),
            p.getString("cover_key_$pkg", ""), p.getString("selected", ""),
        ).joinToString("|")
    }

    /** Content URI for the cover; the version changes whenever the image does, so the launcher reloads it. */
    fun coverUri(ctx: Context, pkg: String): Uri? {
        val f = coverFile(ctx, pkg)
        if (!f.exists()) return null
        return Uri.parse("content://${ctx.packageName}.art/$pkg/${f.lastModified()}")
    }

    fun coverFile(ctx: Context, pkg: String) = File(ctx.filesDir, "cover_$pkg.png")

    /** Square-crops, scales and rounds the cover once, when it arrives. */
    private fun saveArt(ctx: Context, pkg: String, src: Bitmap) {
        val side = min(src.width, src.height)
        val outSide = min(side, 400)
        val scale = outSide.toFloat() / side
        val out = Bitmap.createBitmap(outSide, outSide, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setTranslate(-(src.width - side) / 2f, -(src.height - side) / 2f)
                postScale(scale, scale)
            })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val r = outSide * 0.16f
        Canvas(out).drawRoundRect(RectF(0f, 0f, outSide.toFloat(), outSide.toFloat()), r, r, paint)
        try {
            FileOutputStream(coverFile(ctx, pkg)).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            coverFile(ctx, pkg).delete()
        }
    }

    private fun MediaMetadata.text(key: String): String? = getString(key)?.takeIf { it.isNotBlank() }
}
