package com.jono.audiobookwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Draws both widgets (Audiobooks and Music) with one set of layouts and one code path. A profile
 * only decides the content (books vs songs, albums and mixes) and which controls apply; sizes,
 * layout and behaviour are shared.
 */
object WidgetRenderer {

    /**
     * Every layout, the size (dp) it's designed for, and what it contains. Android shows the layout
     * whose size fits inside the widget and is closest to it. Typical 4-column grid: 1 cell ≈ 80dp
     * wide, 2 ≈ 180, 3 ≈ 270, 4 ≈ 360; rows ≈ 80–100dp. The setup screen lists the real sizes.
     *
     * Rule: use all the space. Small sizes show the essentials and expand to the full panel on tap;
     * strips add recent items; big sizes show everything.
     */
    private enum class Kind(
        val layout: Int,
        val size: SizeF,
        val label: String,
        val text: Boolean = true,
        val timeLeft: Boolean = false,
        val player: Boolean = false,
        val slots: Int = 0,
        val stripActions: Boolean = false,
        val grid: Boolean = false,
        val thumbs: Int = 0,
        val rows: Int = 0,
        val rowViews: Int = rows,
        val bookmarkRows: Int = 0,
        val switcher: Boolean = false,
        val expands: Boolean = true,
    ) {
        TILE(R.layout.widget_tile, SizeF(50f, 50f), "art", text = false),
        STRIP_S(R.layout.widget_strip_s, SizeF(150f, 50f), "art, text, time left", timeLeft = true),
        STRIP(R.layout.widget_strip, SizeF(220f, 50f), "art | text, time left | 2×2 recent grid",
            timeLeft = true, grid = true),
        STRIP_L(R.layout.widget_strip_l, SizeF(320f, 50f), "art, text, time left, quick action, next, recent",
            timeLeft = true, stripActions = true, thumbs = 3),
        SQUARE(R.layout.widget_square, SizeF(150f, 150f), "big art, text"),
        LARGE(R.layout.widget_large, SizeF(260f, 150f), "big art, text, up next, progress, all controls",
            player = true, slots = 3),
        TALL(R.layout.widget_tall, SizeF(150f, 250f), "art, text, progress, controls, recent list",
            player = true, slots = 1, rows = 3),
        XL(R.layout.widget_xl, SizeF(260f, 250f), "everything", player = true, slots = 4, rows = 2, rowViews = 4,
            bookmarkRows = 3, switcher = true, expands = false),
        XL_TALL(R.layout.widget_xl, SizeF(260f, 340f), "everything, longer lists", player = true, slots = 4,
            rows = 4, bookmarkRows = 3, switcher = true, expands = false),
    }

    private val REC = intArrayOf(R.id.rec1, R.id.rec2, R.id.rec3, R.id.rec4)
    private val REC_ART = intArrayOf(R.id.rec1_art, R.id.rec2_art, R.id.rec3_art, R.id.rec4_art)
    private val REC_ICON = intArrayOf(R.id.rec1_icon, R.id.rec2_icon, R.id.rec3_icon, R.id.rec4_icon)
    private val REC_TITLE = intArrayOf(R.id.rec1_title, R.id.rec2_title, R.id.rec3_title, R.id.rec4_title)
    private val REC_SUB = intArrayOf(R.id.rec1_sub, R.id.rec2_sub, R.id.rec3_sub, R.id.rec4_sub)
    private val SLOTS = intArrayOf(R.id.action1, R.id.action2, R.id.action3, R.id.action4)
    private val BM = intArrayOf(R.id.bm1, R.id.bm2, R.id.bm3)
    private val BM_TEXT = intArrayOf(R.id.bm1_text, R.id.bm2_text, R.id.bm3_text)
    private val BM_WHEN = intArrayOf(R.id.bm1_when, R.id.bm2_when, R.id.bm3_when)
    private val SWITCH = intArrayOf(R.id.switch1, R.id.switch2)

    /** In-app views (the expand panel) that mirror the widget and redraw with it. */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** A recent item: a cover in the grid / thumbs, a row in lists. */
    private class Item(val art: Uri?, val title: String, val sub: String, val pkg: String, val tap: PendingIntent)

    /** One of the app's own extra buttons, with the app's own icon. */
    private class Act(val action: String, val label: String, val icon: Bitmap)

    private class Model(
        val profile: Profile,
        val source: Source,
        val setupNeeded: Boolean,
        val hasSession: Boolean,
        val playing: Boolean,
        val title: String,
        val line2: String?,
        val line3: String?,
        val art: Uri?,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float,
        val upNext: String?,
        val seekButtons: Boolean,
        val prevNext: Boolean,
        val showSpeed: Boolean,
        val canSetSpeed: Boolean,
        val bookmarks: Boolean,
        val actions: List<Act>,
        val grid: List<Item>,
        val rows: List<Item>,
        val marks: List<Bookmarks.Mark>,
    )

    fun providerClass(p: Profile): Class<*> = when (p) {
        Profile.BOOKS -> AudiobookWidget::class.java
        Profile.MUSIC -> MusicWidget::class.java
    }

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (p in Profile.values()) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, providerClass(p)))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx, p))
        }
        listeners.forEach { it() }
    }

    /** The full view, for the expand panel. */
    fun buildPanel(ctx: Context, p: Profile): RemoteViews =
        fill(ctx, RemoteViews(ctx.packageName, Kind.XL_TALL.layout), model(ctx, p), Kind.XL_TALL)

    private fun build(ctx: Context, p: Profile): RemoteViews {
        val m = model(ctx, p)
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

    /** One line per widget on the home screen: which widget, its portrait size, the layout it shows. */
    fun describeSizes(ctx: Context): String {
        val mgr = AppWidgetManager.getInstance(ctx)
        val lines = Profile.values().flatMap { p ->
            mgr.getAppWidgetIds(ComponentName(ctx, providerClass(p))).map { id ->
                val o = mgr.getAppWidgetOptions(id)
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                "• ${p.label}: ${w}×${h} dp → ${bestFit(w.toFloat(), h.toFloat()).label}"
            }
        }
        return lines.joinToString("\n").ifEmpty { "No widgets on your home screen yet." }
    }

    // ---------------------------------------------------------------- content per profile

    private fun model(ctx: Context, p: Profile): Model = when (p) {
        Profile.BOOKS -> booksModel(ctx)
        Profile.MUSIC -> musicModel(ctx)
    }

    private fun setupModel(p: Profile, source: Source, title: String) = Model(
        p, source, true, false, false, title, "Tap to allow access", null, null, 0L, 0L, 1f, null,
        false, false, false, false, false, emptyList(), emptyList(), emptyList(), emptyList(),
    )

    private fun booksModel(ctx: Context): Model {
        val source = Sources.byPkg(Store.selected(ctx))?.takeIf { it in Profile.BOOKS.sources } ?: Sources.LIBBY
        if (!Media.hasAccess(ctx)) return setupModel(Profile.BOOKS, source, "Set up audiobook widget")
        val c = Media.controllerFor(ctx, source.pkg)
        val recents = Store.recents(ctx)
        val book = recents.firstOrNull { it.pkg == source.pkg }
        val playingBooks = Media.controllers(ctx, Profile.BOOKS).filter { Media.isPlaying(it) }
            .mapNotNull { pc -> recents.firstOrNull { it.pkg == pc.packageName } }
        val others = distinctBooks(recents, listOfNotNull(book) + playingBooks).mapIndexed { i, b ->
            Item(
                art = Store.coverUri(ctx, b),
                title = b.title,
                sub = listOfNotNull(b.chapter, b.author).joinToString(" · "),
                pkg = b.pkg,
                tap = bookTap(ctx, b, i),
            )
        }
        val speed = c?.let { Media.speed(it) } ?: 1f
        val canSetSpeed = c?.let { Media.supports(it, PlaybackState.ACTION_SET_PLAYBACK_SPEED) } ?: false
        return Model(
            profile = Profile.BOOKS,
            source = source,
            setupNeeded = false,
            hasSession = c != null,
            playing = c?.let { Media.isPlaying(it) } ?: false,
            title = book?.title ?: source.label,
            line2 = book?.chapter ?: if (book == null) "Tap to open ${source.label}" else null,
            line3 = book?.author,
            art = book?.let { Store.coverUri(ctx, it) },
            positionMs = c?.let { Media.positionMs(it) } ?: 0L,
            durationMs = c?.let { Media.duration(it) } ?: 0L,
            speed = speed,
            upNext = c?.let { Media.upNext(it) },
            seekButtons = true,
            prevNext = c?.let {
                Media.supports(it, PlaybackState.ACTION_SKIP_TO_NEXT) ||
                    Media.supports(it, PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            } ?: false,
            showSpeed = canSetSpeed || speed != 1f,
            canSetSpeed = canSetSpeed,
            bookmarks = true,
            actions = emptyList(),
            grid = others,
            rows = others,
            marks = book?.let { Bookmarks.forBook(ctx, it.key) }.orEmpty(),
        )
    }

    private fun musicModel(ctx: Context): Model {
        val source = Sources.YTMUSIC
        if (!Media.hasAccess(ctx)) return setupModel(Profile.MUSIC, source, "Set up music widget")
        val c = Media.controllerFor(ctx, source.pkg)
        val st = c?.playbackState
        val recents = MusicStore.recents(ctx)
        val song = recents.firstOrNull()
        return Model(
            profile = Profile.MUSIC,
            source = source,
            setupNeeded = false,
            hasSession = c != null,
            playing = c?.let { Media.isPlaying(it) } ?: false,
            title = song?.title ?: source.label,
            line2 = song?.artist ?: if (song == null) "Tap to open" else null,
            line3 = song?.album,
            art = song?.let { MusicStore.coverUri(ctx, it) },
            positionMs = c?.let { Media.positionMs(it) } ?: 0L,
            durationMs = c?.let { Media.duration(it) } ?: 0L,
            speed = 1f,
            upNext = if (c != null) MusicStore.upNext(ctx) else null,
            seekButtons = false,
            prevNext = true,
            showSpeed = false,
            canSetSpeed = false,
            bookmarks = false,
            actions = st?.let { appActions(ctx, source.pkg, it) }.orEmpty(),
            // The grid holds albums and mixes; lists hold songs. Neither repeats what's playing.
            grid = MusicStore.otherGroups(ctx).mapIndexed { i, g ->
                Item(MusicStore.coverUri(ctx, g.coverKey), g.title, g.artist.orEmpty(), source.pkg, groupTap(ctx, g, i))
            },
            rows = recents.drop(1).mapIndexed { i, s ->
                Item(
                    MusicStore.coverUri(ctx, s), s.title, listOfNotNull(s.artist, s.album).joinToString(" · "),
                    source.pkg, songTap(ctx, s, i),
                )
            },
            marks = emptyList(),
        )
    }

    /**
     * Recent books with no repeats, and nothing that's in the main spot or playing right now.
     * Matching is by normalized title, and also catches the same book reported differently
     * (one title containing the other, or a recent entry named after the playing chapter).
     */
    private fun distinctBooks(all: List<Store.Book>, current: List<Store.Book>): List<Store.Book> {
        fun norm(t: String?) = t.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        val excluded = current.flatMap { listOf(norm(it.title), norm(it.chapter)) }.filter { it.isNotEmpty() }
        val currentKeys = current.map { it.key }.toSet()
        fun isCurrent(b: Store.Book): Boolean {
            if (b.key in currentKeys) return true
            val t = norm(b.title)
            return excluded.any { e -> t == e || (t.length >= 6 && e.length >= 6 && (t in e || e in t)) }
        }
        val seen = mutableSetOf<String>()
        return all.filter { !isCurrent(it) && seen.add(norm(it.title)) }
    }

    // ---------------------------------------------------------------- drawing (shared)

    private fun fill(ctx: Context, rv: RemoteViews, m: Model, kind: Kind): RemoteViews {
        val p = m.profile
        val setup = activity(ctx, 1, Intent(ctx, MainActivity::class.java))
        val open = openApp(ctx, m.source) ?: setup
        val expand = activity(
            ctx, 3 + p.ordinal,
            Intent(ctx, PanelActivity::class.java).putExtra(PanelActivity.EXTRA_PROFILE, p.name),
        )
        // Smaller sizes: tapping the text opens the full panel (the art is the play button there).
        // Big sizes: tapping the art opens the panel too; the full view opens the app.
        val mainTap = when {
            m.setupNeeded -> setup
            kind.expands -> expand
            else -> open
        }

        // With no live session (app closed), the buttons just open the app so you can resume there.
        fun control(action: String, extra: String? = null) = when {
            m.setupNeeded -> setup
            m.hasSession -> broadcast(ctx, p, action, extra)
            else -> open
        }

        // Art + play/pause + update symbol: every size.
        if (m.art != null) rv.setImageViewUri(R.id.art, m.art)
        else rv.setImageViewResource(R.id.art, R.drawable.art_placeholder)
        rv.setOnClickPendingIntent(R.id.art, mainTap)
        // Small sizes: play/pause fills the art (the left of a strip is one big button).
        // Big sizes: a regular button in the controls row.
        val onArt = !kind.player
        rv.setImageViewResource(
            R.id.play,
            when {
                onArt && m.playing -> R.drawable.ic_pause_big
                onArt -> R.drawable.ic_play_big
                m.playing -> R.drawable.ic_pause
                else -> R.drawable.ic_play
            },
        )
        rv.setContentDescription(R.id.play, if (m.playing) "Pause" else "Play")
        rv.setOnClickPendingIntent(R.id.play, control(MediaWidget.ACTION_PLAY_PAUSE))

        val updater = if (UpdateStatus.available(ctx)) UpdateStatus.updaterIntent(ctx) else null
        if (updater != null) {
            rv.setViewVisibility(R.id.update, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.update, activity(ctx, 4, updater))
        } else {
            rv.setViewVisibility(R.id.update, View.GONE)
        }

        if (kind.text) {
            ClaudeLink.bind(ctx, rv, R.id.claude)
            rv.setOnClickPendingIntent(R.id.content, mainTap)
            val icon = appIcon(ctx, m.source.pkg)
            if (icon != null) {
                rv.setImageViewBitmap(R.id.badge, icon)
                rv.setViewVisibility(R.id.badge, View.VISIBLE)
                // Several apps (Libby, Audible): tap the icon to switch. One app: tap to open it.
                if (p.sources.size > 1 && !m.setupNeeded) {
                    rv.setContentDescription(R.id.badge, "${m.source.label}, tap to switch apps")
                    rv.setOnClickPendingIntent(R.id.badge, broadcast(ctx, p, MediaWidget.ACTION_TOGGLE))
                } else {
                    rv.setContentDescription(R.id.badge, "Open ${m.source.label}")
                    rv.setOnClickPendingIntent(R.id.badge, if (m.setupNeeded) setup else open)
                }
            }
            rv.setTextViewText(R.id.title, m.title)
            textOrGone(rv, R.id.line2, m.line2)
            textOrGone(rv, R.id.line3, m.line3)
        }

        if (kind.timeLeft) setTimeLeft(rv, m)

        if (kind.player) {
            setPlayerTimes(rv, m)
            textOrGone(rv, R.id.up_next, m.upNext?.let { "Next: $it" })
            showIf(rv, R.id.prev, m.prevNext, control(MediaWidget.ACTION_PREV))
            showIf(rv, R.id.next, m.prevNext, control(MediaWidget.ACTION_NEXT))
            showIf(rv, R.id.back, m.seekButtons, control(MediaWidget.ACTION_BACK))
            showIf(rv, R.id.fwd, m.seekButtons, control(MediaWidget.ACTION_FWD))
            showIf(rv, R.id.bookmark, m.bookmarks, control(MediaWidget.ACTION_BOOKMARK))
            if (m.showSpeed) {
                rv.setTextViewText(R.id.speed, speedLabel(m.speed))
                rv.setViewVisibility(R.id.speed, View.VISIBLE)
                if (m.canSetSpeed) rv.setOnClickPendingIntent(R.id.speed, control(MediaWidget.ACTION_SPEED))
            } else {
                rv.setViewVisibility(R.id.speed, View.GONE)
            }
        }

        // 4x1: one quick action (bookmark for books, the app's first button for music) and next.
        if (kind.stripActions) {
            showIf(rv, R.id.bookmark, m.bookmarks, control(MediaWidget.ACTION_BOOKMARK))
            showIf(rv, R.id.next, m.prevNext && !m.bookmarks, control(MediaWidget.ACTION_NEXT))
        }

        val tint = ctx.getColor(R.color.widget_text)
        val slotCount = if (kind.stripActions) 1 else kind.slots
        for (i in 0 until slotCount) {
            val id = SLOTS[i]
            val act = if (kind.stripActions && m.bookmarks) null else m.actions.getOrNull(i)
            if (act == null) {
                rv.setViewVisibility(id, View.GONE)
                continue
            }
            rv.setImageViewBitmap(id, act.icon)
            rv.setInt(id, "setColorFilter", tint)
            rv.setContentDescription(id, act.label)
            rv.setOnClickPendingIntent(id, control(MediaWidget.ACTION_CUSTOM, act.action))
            rv.setViewVisibility(id, View.VISIBLE)
        }

        if (kind.grid) {
            // Empty cells stay blank so the grid keeps its shape; with nothing to show, text takes the room.
            rv.setViewVisibility(R.id.grid, if (m.grid.isEmpty()) View.GONE else View.VISIBLE)
            for (i in 0 until 4) {
                val item = m.grid.getOrNull(i)
                if (item == null) {
                    rv.setViewVisibility(REC[i], View.INVISIBLE)
                    continue
                }
                rv.setViewVisibility(REC[i], View.VISIBLE)
                setArt(rv, REC_ART[i], item)
                rv.setOnClickPendingIntent(REC[i], item.tap)
            }
        }

        for (i in 0 until kind.thumbs) {
            val item = m.grid.getOrNull(i)
            if (item == null) {
                rv.setViewVisibility(REC[i], View.GONE)
                continue
            }
            rv.setViewVisibility(REC[i], View.VISIBLE)
            setArt(rv, REC_ART[i], item)
            rv.setOnClickPendingIntent(REC[i], item.tap)
        }

        if (kind.rows > 0) {
            rv.setViewVisibility(R.id.recent_label, if (m.rows.isEmpty()) View.GONE else View.VISIBLE)
            for (i in 0 until kind.rowViews) {
                val item = if (i < kind.rows) m.rows.getOrNull(i) else null
                if (item == null) {
                    rv.setViewVisibility(REC[i], View.GONE)
                    continue
                }
                rv.setViewVisibility(REC[i], View.VISIBLE)
                setArt(rv, REC_ART[i], item)
                rv.setOnClickPendingIntent(REC[i], item.tap)
                rv.setTextViewText(REC_TITLE[i], item.title)
                rv.setTextViewText(REC_SUB[i], item.sub)
                val icon = appIcon(ctx, item.pkg)
                if (icon != null) {
                    rv.setImageViewBitmap(REC_ICON[i], icon)
                    rv.setViewVisibility(REC_ICON[i], View.VISIBLE)
                }
            }
        }

        if (kind.bookmarkRows > 0) {
            rv.setViewVisibility(R.id.bm_label, if (m.marks.isEmpty()) View.GONE else View.VISIBLE)
            for (i in 0 until kind.bookmarkRows) {
                val b = m.marks.getOrNull(i)
                if (b == null) {
                    rv.setViewVisibility(BM[i], View.GONE)
                    continue
                }
                rv.setViewVisibility(BM[i], View.VISIBLE)
                rv.setTextViewText(BM_TEXT[i], listOfNotNull(b.chapter, Bookmarks.formatPosition(b.positionMs)).joinToString(" · "))
                rv.setTextViewText(
                    BM_WHEN[i],
                    DateUtils.getRelativeTimeSpanString(b.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                )
                rv.setOnClickPendingIntent(BM[i], control(MediaWidget.ACTION_JUMP, b.createdAt.toString()))
            }
        }

        if (kind.switcher) {
            // Only for widgets that follow more than one app (Audiobooks: Libby + Audible).
            val switchable = if (p.sources.size > 1) p.sources else emptyList()
            for (i in SWITCH.indices) {
                val s = switchable.getOrNull(i)
                val icon = s?.let { appIcon(ctx, it.pkg) }
                if (s == null || icon == null) {
                    rv.setViewVisibility(SWITCH[i], View.GONE)
                    continue
                }
                val selected = s == m.source
                rv.setViewVisibility(SWITCH[i], View.VISIBLE)
                rv.setImageViewBitmap(SWITCH[i], icon)
                rv.setContentDescription(SWITCH[i], s.label)
                rv.setInt(SWITCH[i], "setImageAlpha", if (selected) 255 else 110)
                rv.setInt(SWITCH[i], "setBackgroundResource", if (selected) R.drawable.chip_on else 0)
                rv.setOnClickPendingIntent(
                    SWITCH[i], if (selected) open else broadcast(ctx, p, MediaWidget.ACTION_SELECT, s.pkg),
                )
            }
        }
        return rv
    }

    private fun setArt(rv: RemoteViews, id: Int, item: Item) {
        if (item.art != null) rv.setImageViewUri(id, item.art)
        else rv.setImageViewResource(id, R.drawable.art_placeholder)
        rv.setContentDescription(id, item.title)
    }

    private fun showIf(rv: RemoteViews, id: Int, show: Boolean, tap: PendingIntent) {
        rv.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)
        if (show) rv.setOnClickPendingIntent(id, tap)
    }

    private fun textOrGone(rv: RemoteViews, id: Int, text: String?) {
        rv.setTextViewText(id, text ?: "")
        rv.setViewVisibility(id, if (text.isNullOrEmpty()) View.GONE else View.VISIBLE)
    }

    private fun hasProgress(m: Model) = m.hasSession && m.durationMs > 0L && m.positionMs <= m.durationMs

    /**
     * Strips: "1:23 left" in real time (allowing for playback speed), counting down by itself while
     * playing. When paused it's plain text, because a stopped Chronometer recalculates against the
     * clock if the launcher redraws it.
     */
    private fun setTimeLeft(rv: RemoteViews, m: Model) {
        if (!hasProgress(m)) {
            rv.setViewVisibility(R.id.time, View.GONE)
            rv.setViewVisibility(R.id.time_static, View.GONE)
            return
        }
        val left = ((m.durationMs - m.positionMs) / m.speed).toLong().coerceAtLeast(0L)
        if (m.playing) {
            rv.setChronometerCountDown(R.id.time, true)
            rv.setChronometer(R.id.time, SystemClock.elapsedRealtime() + left, "%s left", true)
            rv.setViewVisibility(R.id.time, View.VISIBLE)
            rv.setViewVisibility(R.id.time_static, View.GONE)
        } else {
            rv.setViewVisibility(R.id.time, View.GONE)
            rv.setTextViewText(R.id.time_static, "${clock(left)} left")
            rv.setViewVisibility(R.id.time_static, View.VISIBLE)
        }
    }

    /** Big sizes: elapsed time (self-ticking while playing at 1×), progress bar, length. */
    private fun setPlayerTimes(rv: RemoteViews, m: Model) {
        if (!hasProgress(m)) {
            rv.setViewVisibility(R.id.time_row, View.GONE)
            return
        }
        rv.setViewVisibility(R.id.time_row, View.VISIBLE)
        val permille = (m.positionMs * 1000L / m.durationMs).coerceIn(0L, 1000L).toInt()
        rv.setProgressBar(R.id.progress, 1000, permille, false)
        rv.setTextViewText(R.id.duration, clock(m.durationMs))
        if (m.playing && m.speed == 1f) {
            rv.setChronometerCountDown(R.id.time, false)
            rv.setChronometer(R.id.time, SystemClock.elapsedRealtime() - m.positionMs, null, true)
            rv.setViewVisibility(R.id.time, View.VISIBLE)
            rv.setViewVisibility(R.id.time_static, View.GONE)
        } else {
            rv.setViewVisibility(R.id.time, View.GONE)
            rv.setTextViewText(R.id.time_static, clock(m.positionMs))
            rv.setViewVisibility(R.id.time_static, View.VISIBLE)
        }
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000L
        val h = total / 3600L
        val mm = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, mm, s)
        else String.format(Locale.US, "%d:%02d", mm, s)
    }

    private fun speedLabel(speed: Float): String =
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

    // ---------------------------------------------------------------- taps

    /**
     * Tapping a recent item plays it in its app, without opening the app (see Player / PlayActivity).
     * Each slot has its own request code so its PendingIntent keeps its own item.
     */
    private fun playTap(
        ctx: Context, code: Int, pkg: String, resume: Boolean, mediaId: String?, query: String, search: Bundle,
    ): PendingIntent {
        val intent = Intent(ctx, PlayActivity::class.java)
            .putExtra(PlayActivity.EXTRA_PKG, pkg)
            .putExtra(PlayActivity.EXTRA_RESUME, resume)
            .putExtra(PlayActivity.EXTRA_MEDIA_ID, mediaId)
            .putExtra(PlayActivity.EXTRA_QUERY, query)
            .putExtra(PlayActivity.EXTRA_SEARCH, search)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return activity(ctx, code, intent)
    }

    /** A book: resume it if it's what that app last had, otherwise ask the app to play it by id or title. */
    private fun bookTap(ctx: Context, b: Store.Book, index: Int): PendingIntent {
        val isAppCurrent = Store.current(ctx, b.pkg)?.key == b.key
        val search = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_TITLE, b.title)
            b.author?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        }
        val query = listOfNotNull(b.title, b.author).joinToString(" ")
        return playTap(ctx, 300 + index, b.pkg, isAppCurrent, b.mediaId, query, search)
    }

    /** An album or mix: ask YouTube Music to find and play it. */
    private fun groupTap(ctx: Context, g: MusicStore.Group, index: Int): PendingIntent {
        val search = Bundle().apply {
            if (g.kind == MusicStore.GROUP_MIX) {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_PLAYLIST, g.title)
            } else {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_ALBUM, g.title)
            }
            g.artist?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        }
        val query = listOfNotNull(g.title, g.artist).joinToString(" ")
        return playTap(ctx, 100 + index, Sources.YTMUSIC.pkg, false, null, query, search)
    }

    /** A song: ask YouTube Music to find and play it. */
    private fun songTap(ctx: Context, s: MusicStore.Song, index: Int): PendingIntent {
        val search = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_TITLE, s.title)
            s.artist?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        }
        val query = listOfNotNull(s.title, s.artist).joinToString(" ")
        return playTap(ctx, 200 + index, Sources.YTMUSIC.pkg, false, null, query, search)
    }

    // ---------------------------------------------------------------- the app's own buttons

    /** The app's extra buttons with its own icons, so liked/unliked shows as the app draws it. */
    private fun appActions(ctx: Context, pkg: String, st: PlaybackState): List<Act> =
        st.customActions.sortedBy { rank(it.name.toString()) }.mapNotNull { custom ->
            val icon = actionIcon(ctx, pkg, custom) ?: return@mapNotNull null
            Act(custom.action, custom.name.toString(), icon)
        }

    private fun rank(name: String): Int {
        val n = name.lowercase(Locale.US)
        return when {
            "dislike" in n || "thumbs down" in n || "thumb down" in n -> 1
            "like" in n || "thumb" in n -> 0
            else -> 2
        }
    }

    private val iconCache = mutableMapOf<String, Bitmap>()

    /** The icon the app supplies for a button; a plain thumb if it can't be loaded. */
    private fun actionIcon(ctx: Context, pkg: String, custom: PlaybackState.CustomAction): Bitmap? {
        val key = "action:$pkg:${custom.icon}"
        iconCache[key]?.let { return it }
        val drawable: Drawable = try {
            ctx.packageManager.getResourcesForApplication(pkg).getDrawable(custom.icon, null)
        } catch (e: Exception) {
            null
        } ?: fallbackIcon(ctx, custom.name.toString()) ?: return null
        return rasterize(drawable, 64).also { iconCache[key] = it }
    }

    private fun fallbackIcon(ctx: Context, name: String): Drawable? = when (rank(name)) {
        0 -> ctx.getDrawable(R.drawable.ic_thumb_up)
        1 -> ctx.getDrawable(R.drawable.ic_thumb_down)
        else -> null
    }

    /** The app's own launcher icon, as installed on the phone, drawn small. */
    private fun appIcon(ctx: Context, pkg: String): Bitmap? {
        iconCache[pkg]?.let { return it }
        val drawable = try {
            ctx.packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        return rasterize(drawable, 72).also { iconCache[pkg] = it }
    }

    private fun rasterize(drawable: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        return bmp
    }

    // ---------------------------------------------------------------- intents

    private fun openApp(ctx: Context, source: Source): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(source.pkg) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return activity(ctx, 10 + Sources.all.indexOf(source), launch)
    }

    private fun activity(ctx: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** A tap handled by the profile's widget provider. The data URI keeps different extras distinct. */
    private fun broadcast(ctx: Context, p: Profile, action: String, extra: String? = null): PendingIntent {
        val intent = Intent(ctx, providerClass(p))
            .setAction(action)
            .setData(Uri.fromParts("mw", extra ?: "-", null))
        return PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
