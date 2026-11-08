package com.jono.audiobookwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

object WidgetRenderer {

    /**
     * Every layout, the size (dp) it's designed for, and what it contains. Android shows the layout
     * whose size fits inside the widget and is closest to it. Typical 4-column grid: 1 cell ≈ 80dp
     * wide, 2 ≈ 180, 3 ≈ 270, 4 ≈ 360; rows ≈ 80–100dp. The setup screen lists the real sizes.
     *
     * Rule: use all the space. Small sizes show the essentials and expand to the full panel on tap;
     * long strips add recent books; big sizes show everything.
     */
    private enum class Kind(
        val layout: Int,
        val size: SizeF,
        val label: String,
        val text: Boolean = true,
        val time: Boolean = false,
        val controls: Boolean = false,
        val chapterButtons: Boolean = false,
        val switcher: Boolean = false,
        val recentSlots: Int = 0,
        val recentRows: Boolean = false,
        val recentIcons: Boolean = recentRows,
        val expands: Boolean = true,
    ) {
        TILE(R.layout.widget_tile, SizeF(50f, 50f), "cover", text = false),
        STRIP_S(R.layout.widget_strip_s, SizeF(150f, 50f), "cover, title, chapter, time left, author", time = true),
        STRIP(R.layout.widget_strip, SizeF(220f, 50f), "cover | text, time left | previous book",
            time = true, recentSlots = 1, recentIcons = true),
        STRIP_L(R.layout.widget_strip_l, SizeF(320f, 50f), "cover, text, time left, recent covers",
            time = true, recentSlots = 3),
        SQUARE(R.layout.widget_square, SizeF(150f, 150f), "big cover, title, chapter"),
        LARGE(R.layout.widget_large, SizeF(260f, 150f), "big cover, text, all controls",
            time = true, controls = true, chapterButtons = true),
        TALL(R.layout.widget_tall, SizeF(150f, 250f), "cover, text, controls, recent books",
            time = true, controls = true, recentSlots = 3, recentRows = true),
        XL(R.layout.widget_xl, SizeF(260f, 250f), "everything", time = true, controls = true,
            chapterButtons = true, switcher = true, recentSlots = 4, recentRows = true, expands = false),
    }

    private class Model(
        val source: Source,
        val setupNeeded: Boolean,
        val hasSession: Boolean,
        val playing: Boolean,
        val book: Store.Book?,
        val art: Uri?,
        val fallback: String,
        val remainingMs: Long?,
        val speed: Float,
        val canSetSpeed: Boolean,
        val canSkipChapters: Boolean,
        val recents: List<Store.Book>,
    )

    private val REC = intArrayOf(R.id.rec1, R.id.rec2, R.id.rec3, R.id.rec4)
    private val REC_ART = intArrayOf(R.id.rec1_art, R.id.rec2_art, R.id.rec3_art, R.id.rec4_art)
    private val REC_ICON = intArrayOf(R.id.rec1_icon, R.id.rec2_icon, R.id.rec3_icon, R.id.rec4_icon)
    private val REC_TITLE = intArrayOf(R.id.rec1_title, R.id.rec2_title, R.id.rec3_title, R.id.rec4_title)
    private val REC_SUB = intArrayOf(R.id.rec1_sub, R.id.rec2_sub, R.id.rec3_sub, R.id.rec4_sub)

    /** In-app views (the expand panel) that mirror the widget and redraw with it. */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, AudiobookWidget::class.java))
        if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx))
        listeners.forEach { it() }
    }

    /** The full (XL) view, for the expand panel. */
    fun buildPanel(ctx: Context): RemoteViews =
        fill(ctx, RemoteViews(ctx.packageName, Kind.XL.layout), model(ctx), Kind.XL)

    private fun build(ctx: Context): RemoteViews {
        val m = model(ctx)
        return RemoteViews(
            Kind.values().associate { kind ->
                kind.size to fill(ctx, RemoteViews(ctx.packageName, kind.layout), m, kind)
            }
        )
    }

    /** Same rule Android uses to pick a layout, so the setup screen can say which one you'll get. */
    private fun bestFit(w: Float, h: Float): Kind {
        val fitting = Kind.values().filter { it.size.width <= w && it.size.height <= h }
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

    private fun model(ctx: Context): Model {
        val source = Sources.byPkg(Store.selected(ctx)) ?: Sources.LIBBY
        val recents = Store.recents(ctx)
        if (!Media.hasAccess(ctx)) {
            return Model(
                source, true, false, false, null, null, "Tap to allow access",
                null, 1f, false, false, emptyList(),
            )
        }
        val c = Media.controllerFor(ctx, source.pkg)
        val book = recents.firstOrNull { it.pkg == source.pkg }
        return Model(
            source = source,
            setupNeeded = false,
            hasSession = c != null,
            playing = c?.let { Media.isPlaying(it) } ?: false,
            book = book,
            art = book?.let { Store.coverUri(ctx, it) },
            fallback = if (book == null) "Tap to open ${source.label}" else "",
            remainingMs = c?.let { Media.remainingMs(it) },
            speed = c?.let { Media.speed(it) } ?: 1f,
            canSetSpeed = c?.let { Media.supports(it, PlaybackState.ACTION_SET_PLAYBACK_SPEED) } ?: false,
            canSkipChapters = c?.let {
                Media.supports(it, PlaybackState.ACTION_SKIP_TO_NEXT) ||
                    Media.supports(it, PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            } ?: false,
            recents = recents.filter { it.key != book?.key },
        )
    }

    private fun fill(ctx: Context, rv: RemoteViews, m: Model, kind: Kind): RemoteViews {
        val setup = activity(ctx, 1, Intent(ctx, MainActivity::class.java))
        val open = openApp(ctx, m.source) ?: setup
        val expand = activity(ctx, 3, Intent(ctx, PanelActivity::class.java))
        // Small sizes: tapping the cover or text opens the full panel. The full view opens the app.
        val mainTap = when {
            m.setupNeeded -> setup
            kind.expands -> expand
            else -> open
        }

        // With no live session (app closed), the buttons just open the app so you can resume there.
        fun control(action: String) = when {
            m.setupNeeded -> setup
            m.hasSession -> broadcast(ctx, action)
            else -> open
        }

        // Cover + play/pause: every size.
        if (m.art != null) rv.setImageViewUri(R.id.art, m.art)
        else rv.setImageViewResource(R.id.art, R.drawable.art_placeholder)
        rv.setOnClickPendingIntent(R.id.art, mainTap)
        rv.setImageViewResource(R.id.play, if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        rv.setContentDescription(R.id.play, if (m.playing) "Pause" else "Play")
        rv.setOnClickPendingIntent(R.id.play, control(AudiobookWidget.ACTION_PLAY_PAUSE))

        if (kind.text) {
            rv.setOnClickPendingIntent(R.id.content, mainTap)
            val icon = appIcon(ctx, m.source.pkg)
            if (icon != null) {
                rv.setImageViewBitmap(R.id.badge, icon)
                rv.setContentDescription(R.id.badge, "${m.source.label}, tap to switch apps")
                rv.setViewVisibility(R.id.badge, View.VISIBLE)
                rv.setOnClickPendingIntent(R.id.badge, broadcast(ctx, AudiobookWidget.ACTION_TOGGLE))
            }
            val title = m.book?.title ?: if (m.setupNeeded) "Set up audiobook widget" else m.source.label
            rv.setTextViewText(R.id.title, title)
            textOrGone(rv, R.id.chapter, m.book?.chapter ?: m.fallback.ifEmpty { null })
            textOrGone(rv, R.id.author, m.book?.author)
            if (kind.time) {
                val rem = m.remainingMs
                if (rem != null) {
                    rv.setChronometerCountDown(R.id.time, true)
                    rv.setChronometer(R.id.time, SystemClock.elapsedRealtime() + rem, "%s left", m.playing)
                    rv.setViewVisibility(R.id.time, View.VISIBLE)
                } else {
                    rv.setViewVisibility(R.id.time, View.GONE)
                }
            }
        }

        if (kind.controls) {
            for ((id, action) in listOf(R.id.back to AudiobookWidget.ACTION_BACK, R.id.fwd to AudiobookWidget.ACTION_FWD)) {
                rv.setViewVisibility(id, View.VISIBLE)
                rv.setOnClickPendingIntent(id, control(action))
            }
            if (kind.chapterButtons) {
                val show = if (m.canSkipChapters) View.VISIBLE else View.GONE
                rv.setViewVisibility(R.id.prev, show)
                rv.setViewVisibility(R.id.next, show)
                rv.setOnClickPendingIntent(R.id.prev, control(AudiobookWidget.ACTION_PREV))
                rv.setOnClickPendingIntent(R.id.next, control(AudiobookWidget.ACTION_NEXT))
            }
            if (m.canSetSpeed || m.speed != 1f) {
                rv.setTextViewText(R.id.speed, speedLabel(m.speed))
                rv.setViewVisibility(R.id.speed, View.VISIBLE)
                if (m.canSetSpeed) rv.setOnClickPendingIntent(R.id.speed, control(AudiobookWidget.ACTION_SPEED))
            } else {
                rv.setViewVisibility(R.id.speed, View.GONE)
            }
        }

        if (kind.switcher) {
            switchIcon(ctx, rv, R.id.switch_libby, Sources.LIBBY, m, open)
            switchIcon(ctx, rv, R.id.switch_audible, Sources.AUDIBLE, m, open)
        }

        for (i in 0 until kind.recentSlots) {
            val r = m.recents.getOrNull(i)
            if (r == null) {
                rv.setViewVisibility(REC[i], View.GONE)
                continue
            }
            rv.setViewVisibility(REC[i], View.VISIBLE)
            val uri = Store.coverUri(ctx, r)
            if (uri != null) rv.setImageViewUri(REC_ART[i], uri)
            else rv.setImageViewResource(REC_ART[i], R.drawable.art_placeholder)
            rv.setContentDescription(REC_ART[i], r.title)
            rv.setOnClickPendingIntent(REC[i], recentTap(ctx, r, setup))
            if (kind.recentRows) {
                rv.setTextViewText(REC_TITLE[i], r.title)
                rv.setTextViewText(REC_SUB[i], listOfNotNull(r.chapter, r.author).joinToString(" · "))
            }
            if (kind.recentIcons) {
                val icon = appIcon(ctx, r.pkg)
                if (icon != null) {
                    rv.setImageViewBitmap(REC_ICON[i], icon)
                    rv.setViewVisibility(REC_ICON[i], View.VISIBLE)
                }
            }
        }
        if (kind.recentRows) {
            rv.setViewVisibility(R.id.recent_label, if (m.recents.isEmpty()) View.GONE else View.VISIBLE)
        }
        return rv
    }

    /** Another app's current book: switch the widget to it. An older book: open its app. */
    private fun recentTap(ctx: Context, r: Store.Book, fallback: PendingIntent): PendingIntent {
        val isAppCurrent = Store.current(ctx, r.pkg)?.key == r.key
        if (isAppCurrent) return broadcast(ctx, AudiobookWidget.ACTION_SELECT, r.pkg)
        val source = Sources.byPkg(r.pkg) ?: return fallback
        return openApp(ctx, source) ?: fallback
    }

    private fun textOrGone(rv: RemoteViews, id: Int, text: String?) {
        rv.setTextViewText(id, text ?: "")
        rv.setViewVisibility(id, if (text.isNullOrEmpty()) View.GONE else View.VISIBLE)
    }

    private fun speedLabel(speed: Float): String {
        val s = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
        return "$s×"
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

    private val iconCache = mutableMapOf<String, Bitmap>()

    /** The app's own launcher icon, as installed on the phone, drawn small. */
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
