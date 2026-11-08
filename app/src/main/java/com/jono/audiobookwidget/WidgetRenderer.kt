package com.jono.audiobookwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

object WidgetRenderer {

    /** Every size the widget can take. Android picks the biggest one that fits the widget's current size. */
    private enum class Kind(val layout: Int, val minSize: SizeF) {
        TILE(R.layout.widget_tile, SizeF(40f, 40f)),           // 1×1: cover + play/pause
        SMALL(R.layout.widget_small, SizeF(100f, 40f)),        // 2×1: cover, play/pause
        TILE_BIG(R.layout.widget_tile, SizeF(100f, 100f)),     // 2×2: big cover + play/pause
        COMPACT(R.layout.widget_compact, SizeF(170f, 40f)),    // 3×1, 4×1: cover, title, author, play/pause
        LARGE(R.layout.widget_large, SizeF(170f, 130f)),       // 3×2, 4×2+: app chips, skip buttons
    }

    private class Model(
        val source: Source,
        val setupNeeded: Boolean,
        val hasSession: Boolean,
        val playing: Boolean,
        val title: String,
        val detail: String,
        val art: Uri?,
    )

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, AudiobookWidget::class.java))
        if (ids.isEmpty()) return
        mgr.updateAppWidget(ids, build(ctx))
    }

    private fun build(ctx: Context): RemoteViews {
        val m = model(ctx)
        return RemoteViews(
            Kind.values().associate { kind ->
                kind.minSize to fill(ctx, RemoteViews(ctx.packageName, kind.layout), m, kind)
            }
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
            art = Store.coverUri(ctx, source.pkg),
        )
    }

    private fun fill(ctx: Context, rv: RemoteViews, m: Model, kind: Kind): RemoteViews {
        // Shared by every layout: cover and play/pause.
        if (m.art != null) rv.setImageViewUri(R.id.art, m.art)
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
        rv.setOnClickPendingIntent(R.id.play, control(AudiobookWidget.ACTION_PLAY_PAUSE))

        when (kind) {
            Kind.TILE, Kind.TILE_BIG -> {
                // Tapping the cover around the button opens the app.
                rv.setOnClickPendingIntent(R.id.art, if (m.setupNeeded) setup else open)
                val buttonDp = if (kind == Kind.TILE) 40f else 56f
                rv.setViewLayoutWidth(R.id.play, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                rv.setViewLayoutHeight(R.id.play, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                val padDp = buttonDp * 0.25f
                val pad = (padDp * ctx.resources.displayMetrics.density).toInt()
                rv.setViewPadding(R.id.play, pad, pad, pad, pad)
            }
            Kind.SMALL -> {
                rv.setOnClickPendingIntent(R.id.content, if (m.setupNeeded) setup else open)
            }
            Kind.COMPACT -> {
                setText(rv, m)
                rv.setOnClickPendingIntent(R.id.content, if (m.setupNeeded) setup else open)
                rv.setTextViewText(R.id.source, "${m.source.label.uppercase()}  ⇄")
                rv.setOnClickPendingIntent(R.id.source, broadcast(ctx, AudiobookWidget.ACTION_TOGGLE))
            }
            Kind.LARGE -> {
                setText(rv, m)
                rv.setOnClickPendingIntent(R.id.content, if (m.setupNeeded) setup else open)
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
            }
        }
        return rv
    }

    private fun setText(rv: RemoteViews, m: Model) {
        rv.setTextViewText(R.id.title, m.title)
        rv.setTextViewText(R.id.detail, m.detail)
        rv.setViewVisibility(R.id.detail, if (m.detail.isEmpty()) View.GONE else View.VISIBLE)
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
}
