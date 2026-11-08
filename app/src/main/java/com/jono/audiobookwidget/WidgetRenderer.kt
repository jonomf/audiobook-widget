package com.jono.audiobookwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import kotlin.math.min

object WidgetRenderer {

    private class Model(
        val source: Source,
        val setupNeeded: Boolean,
        val hasSession: Boolean,
        val playing: Boolean,
        val title: String,
        val detail: String,
        val art: Bitmap?,
    )

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, AudiobookWidget::class.java))
        if (ids.isEmpty()) return
        mgr.updateAppWidget(ids, build(ctx))
    }

    /** One row when the widget is short (4×1); full controls and app chips when it's taller. */
    private fun build(ctx: Context): RemoteViews {
        val m = model(ctx)
        val compact = fill(ctx, RemoteViews(ctx.packageName, R.layout.widget_compact), m, large = false)
        val large = fill(ctx, RemoteViews(ctx.packageName, R.layout.widget_large), m, large = true)
        return RemoteViews(
            mapOf(
                SizeF(180f, 40f) to compact,
                SizeF(180f, 140f) to large,
            )
        )
    }

    private fun model(ctx: Context): Model {
        val source = Sources.byPkg(Store.selected(ctx)) ?: Sources.LIBBY
        if (!Media.hasAccess(ctx)) {
            return Model(source, true, false, false, "Set up audiobook widget", "Tap to allow access", null)
        }
        val controller = Media.controllerFor(ctx, source.pkg)
        val book = Store.book(ctx, source.pkg)
        val detail = book?.detail?.takeIf { it.isNotBlank() }
            ?: if (book == null) "Tap to open ${source.label}" else ""
        return Model(
            source = source,
            setupNeeded = false,
            hasSession = controller != null,
            playing = controller?.let { Media.isPlaying(it) } ?: false,
            title = book?.title ?: source.label,
            detail = detail,
            art = Store.loadArt(ctx, source.pkg)?.let { rounded(it) },
        )
    }

    private fun fill(ctx: Context, rv: RemoteViews, m: Model, large: Boolean): RemoteViews {
        rv.setTextViewText(R.id.title, m.title)
        rv.setTextViewText(R.id.detail, m.detail)
        rv.setViewVisibility(R.id.detail, if (m.detail.isEmpty()) View.GONE else View.VISIBLE)
        if (m.art != null) rv.setImageViewBitmap(R.id.art, m.art)
        else rv.setImageViewResource(R.id.art, R.drawable.art_placeholder)
        rv.setImageViewResource(R.id.play, if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        rv.setContentDescription(R.id.play, if (m.playing) "Pause" else "Play")

        val setup = activity(ctx, 1, Intent(ctx, MainActivity::class.java))
        val open = openApp(ctx, m.source) ?: setup

        // With no live session (app closed), the buttons just open the app so you can resume there.
        fun control(action: String) = when {
            m.setupNeeded -> setup
            m.hasSession -> broadcast(ctx, action)
            else -> open
        }

        rv.setOnClickPendingIntent(R.id.content, if (m.setupNeeded) setup else open)
        rv.setOnClickPendingIntent(R.id.play, control(AudiobookWidget.ACTION_PLAY_PAUSE))

        if (large) {
            rv.setOnClickPendingIntent(R.id.back, control(AudiobookWidget.ACTION_BACK))
            rv.setOnClickPendingIntent(R.id.fwd, control(AudiobookWidget.ACTION_FWD))

            val libby = m.source == Sources.LIBBY
            rv.setViewVisibility(R.id.chip_libby_on, if (libby) View.VISIBLE else View.GONE)
            rv.setViewVisibility(R.id.chip_libby_off, if (libby) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.chip_audible_on, if (libby) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.chip_audible_off, if (libby) View.VISIBLE else View.GONE)
            rv.setOnClickPendingIntent(R.id.chip_libby_on, open)
            rv.setOnClickPendingIntent(R.id.chip_audible_on, open)
            rv.setOnClickPendingIntent(
                R.id.chip_libby_off, broadcast(ctx, AudiobookWidget.ACTION_SELECT, Sources.LIBBY.pkg)
            )
            rv.setOnClickPendingIntent(
                R.id.chip_audible_off, broadcast(ctx, AudiobookWidget.ACTION_SELECT, Sources.AUDIBLE.pkg)
            )
        } else {
            rv.setTextViewText(R.id.source, "${m.source.label.uppercase()}  ⇄")
            rv.setOnClickPendingIntent(R.id.source, broadcast(ctx, AudiobookWidget.ACTION_TOGGLE))
        }
        return rv
    }

    private fun openApp(ctx: Context, source: Source): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(source.pkg) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return activity(ctx, 10 + Sources.all.indexOf(source), launch)
    }

    private fun activity(ctx: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun broadcast(ctx: Context, action: String, pkg: String? = null): PendingIntent {
        // The data URI keeps "select Libby" and "select Audible" as distinct PendingIntents.
        val intent = Intent(ctx, AudiobookWidget::class.java)
            .setAction(action)
            .setData(Uri.fromParts("abw", pkg ?: "-", null))
        return PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Square-crops the cover and rounds its corners. */
    private fun rounded(src: Bitmap): Bitmap {
        val size = min(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setTranslate(-(src.width - size) / 2f, -(src.height - size) / 2f)
            })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        val r = size * 0.16f
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        return out
    }
}
