package com.jono.audiobookwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

object WidgetRenderer {

    /**
     * Every layout, with the size (dp) it's designed for. Android shows the layout whose size
     * fits inside the widget and is closest to it. Typical 4-column grid: 1 cell ≈ 80dp wide,
     * 2 ≈ 180, 3 ≈ 270, 4 ≈ 360; one row ≈ 70–100dp tall. The setup screen lists the real sizes.
     */
    private enum class Kind(val layout: Int, val size: SizeF, val label: String) {
        TILE(R.layout.widget_tile, SizeF(50f, 50f), "cover + button"),
        SMALL(R.layout.widget_small, SizeF(100f, 50f), "cover, button"),
        NARROW(R.layout.widget_narrow, SizeF(150f, 50f), "cover + button, title, chapter, author"),
        TILE_BIG(R.layout.widget_tile, SizeF(120f, 120f), "big cover + button"),
        COMPACT(R.layout.widget_compact, SizeF(220f, 50f), "cover, title, chapter, author, button"),
        LARGE(R.layout.widget_large, SizeF(220f, 130f), "full, with app switcher and skips"),
    }

    /** Same rule Android uses to pick a layout, so the setup screen can say which one you'll get. */
    private fun bestFit(w: Float, h: Float): Kind {
        val fitting = Kind.values().filter { it.size.width <= w && it.size.height <= h }
        if (fitting.isEmpty()) return Kind.TILE
        return fitting.minByOrNull {
            val dw = w - it.size.width
            val dh = h - it.size.height
            dw * dw + dh * dh
        } ?: Kind.TILE
    }

    /** One line per widget on the home screen: its portrait size and the layout it shows. */
    fun describeSizes(ctx: Context): String {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, AudiobookWidget::class.java))
        if (ids.isEmpty()) return "No widget on your home screen yet."
        return ids.joinToString("\n") { id ->
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            "• ${w}×${h} dp → ${bestFit(w.toFloat(), h.toFloat()).label}"
        }
    }

    private class Model(
        val source: Source,
        val setupNeeded: Boolean,
        val hasSession: Boolean,
        val playing: Boolean,
        val title: String,
        val chapter: String?,
        val author: String?,
        val fallback: String,
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
                kind.size to fill(ctx, RemoteViews(ctx.packageName, kind.layout), m, kind)
            }
        )
    }

    private fun model(ctx: Context): Model {
        val source = Sources.byPkg(Store.selected(ctx)) ?: Sources.LIBBY
        if (!Media.hasAccess(ctx)) {
            return Model(
                source, true, false, false, "Set up audiobook widget", null, null, "Tap to allow access", null
            )
        }
        val controller = Media.controllerFor(ctx, source.pkg)
        val book = Store.book(ctx, source.pkg)
        return Model(
            source = source,
            setupNeeded = false,
            hasSession = controller != null,
            playing = controller?.let { Media.isPlaying(it) } ?: false,
            title = book?.title ?: source.label,
            chapter = book?.chapter,
            author = book?.author,
            fallback = if (book == null) "Tap to open ${source.label}" else "",
            art = Store.coverUri(ctx, source.pkg),
        )
    }

    private fun fill(ctx: Context, rv: RemoteViews, m: Model, kind: Kind): RemoteViews {
        // Every layout: cover and play/pause.
        if (m.art != null) rv.setImageViewUri(R.id.art, m.art)
        else rv.setImageViewResource(R.id.art, R.drawable.art_placeholder)
        rv.setImageViewResource(R.id.play, if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        rv.setContentDescription(R.id.play, if (m.playing) "Pause" else "Play")

        val setup = activity(ctx, 1, Intent(ctx, MainActivity::class.java))
        val open = openApp(ctx, m.source) ?: setup
        val toggleApp = broadcast(ctx, AudiobookWidget.ACTION_TOGGLE)

        // With no live session (app closed), the buttons just open the app so you can resume there.
        fun control(action: String) = when {
            m.setupNeeded -> setup
            m.hasSession -> broadcast(ctx, action)
            else -> open
        }
        rv.setOnClickPendingIntent(R.id.play, control(AudiobookWidget.ACTION_PLAY_PAUSE))

        // Update dot: hidden. This app has no internet access, so it can't know about new builds.
        rv.setViewVisibility(R.id.update, View.GONE)

        // App icon (beside the title, or under a tile's cover). Tap it to switch apps.
        // The large layout has its own two-icon switcher instead.
        if (kind != Kind.LARGE) {
            val icon = appIcon(ctx, m.source.pkg)
            if (icon != null) {
                rv.setImageViewBitmap(R.id.badge, icon)
                rv.setContentDescription(R.id.badge, "${m.source.label}, tap to switch apps")
                rv.setViewVisibility(R.id.badge, View.VISIBLE)
                rv.setOnClickPendingIntent(R.id.badge, toggleApp)
            }
        }

        when (kind) {
            Kind.TILE, Kind.TILE_BIG -> {
                // Tapping the cover around the button opens the app.
                rv.setOnClickPendingIntent(R.id.art, if (m.setupNeeded) setup else open)
                val buttonDp = if (kind == Kind.TILE) 36f else 56f
                rv.setViewLayoutWidth(R.id.play, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                rv.setViewLayoutHeight(R.id.play, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                val pad = (buttonDp * 0.25f * ctx.resources.displayMetrics.density).toInt()
                rv.setViewPadding(R.id.play, pad, pad, pad, pad)
            }
            Kind.SMALL -> {
                rv.setOnClickPendingIntent(R.id.content, if (m.setupNeeded) setup else open)
            }
            Kind.NARROW, Kind.COMPACT -> {
                setText(rv, m)
                rv.setOnClickPendingIntent(R.id.content, if (m.setupNeeded) setup else open)
                // In the narrow layout the play button sits on the cover, so the whole cover toggles.
                if (kind == Kind.NARROW) {
                    rv.setOnClickPendingIntent(R.id.art, control(AudiobookWidget.ACTION_PLAY_PAUSE))
                } else {
                    rv.setOnClickPendingIntent(R.id.art, if (m.setupNeeded) setup else open)
                }
            }
            Kind.LARGE -> {
                setText(rv, m)
                rv.setOnClickPendingIntent(R.id.content, if (m.setupNeeded) setup else open)
                rv.setOnClickPendingIntent(R.id.art, if (m.setupNeeded) setup else open)
                rv.setOnClickPendingIntent(R.id.back, control(AudiobookWidget.ACTION_BACK))
                rv.setOnClickPendingIntent(R.id.fwd, control(AudiobookWidget.ACTION_FWD))
                switchIcon(ctx, rv, R.id.switch_libby, Sources.LIBBY, m, open)
                switchIcon(ctx, rv, R.id.switch_audible, Sources.AUDIBLE, m, open)
            }
        }
        return rv
    }

    /** Selected app: highlighted, tap opens it. Other app: dimmed, tap switches to it. */
    private fun switchIcon(ctx: Context, rv: RemoteViews, id: Int, source: Source, m: Model, open: PendingIntent) {
        val icon = appIcon(ctx, source.pkg)
        if (icon == null) {
            rv.setViewVisibility(id, View.GONE)
            return
        }
        val selected = source == m.source
        rv.setViewVisibility(id, View.VISIBLE)
        rv.setImageViewBitmap(id, icon)
        rv.setInt(id, "setImageAlpha", if (selected) 255 else 110)
        rv.setInt(id, "setBackgroundResource", if (selected) R.drawable.chip_on else 0)
        rv.setOnClickPendingIntent(
            id, if (selected) open else broadcast(ctx, AudiobookWidget.ACTION_SELECT, source.pkg)
        )
    }

    /** Title, then chapter, then author, each on its own line. Short rows clip the lowest lines first. */
    private fun setText(rv: RemoteViews, m: Model) {
        rv.setTextViewText(R.id.title, m.title)
        val chapter = m.chapter ?: m.fallback.takeIf { m.author == null && it.isNotEmpty() }
        rv.setTextViewText(R.id.chapter, chapter ?: "")
        rv.setViewVisibility(R.id.chapter, if (chapter.isNullOrEmpty()) View.GONE else View.VISIBLE)
        rv.setTextViewText(R.id.author, m.author ?: "")
        rv.setViewVisibility(R.id.author, if (m.author.isNullOrEmpty()) View.GONE else View.VISIBLE)
    }

    private val iconCache = mutableMapOf<String, Bitmap>()

    /** The app's own launcher icon, as installed on the phone, drawn small for the badge. */
    private fun appIcon(ctx: Context, pkg: String): Bitmap? {
        iconCache[pkg]?.let { return it }
        val drawable = try {
            ctx.packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        iconCache[pkg] = bmp
        return bmp
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
