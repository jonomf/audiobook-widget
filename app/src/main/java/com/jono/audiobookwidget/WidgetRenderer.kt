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
        Profile.PODCASTS -> PodcastWidget::class.java
    }

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (p in Profile.values()) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, providerClass(p)))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx, p))
        }
        val mixIds = mgr.getAppWidgetIds(ComponentName(ctx, NowPlayingWidget::class.java))
        if (mixIds.isNotEmpty()) mgr.updateAppWidget(mixIds, buildMix(ctx))
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
        val mix = mgr.getAppWidgetIds(ComponentName(ctx, NowPlayingWidget::class.java)).map { id ->
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            "• Now Playing: ${w}×${h} dp → ${bestFitMix(w.toFloat(), h.toFloat()).label}"
        }
        return (lines + mix).joinToString("\n").ifEmpty { "No widgets on your home screen yet." }
    }

    // ---------------------------------------------------------------- content per profile

    private fun model(ctx: Context, p: Profile): Model = when (p) {
        Profile.BOOKS -> itemsModel(ctx, Profile.BOOKS, Store, "Set up audiobook widget")
        Profile.MUSIC -> musicModel(ctx)
        Profile.PODCASTS -> itemsModel(ctx, Profile.PODCASTS, PodcastStore, "Set up podcast widget")
    }

    private fun setupModel(p: Profile, source: Source, title: String) = Model(
        p, source, true, false, false, title, "Tap to allow access", null, null, 0L, 0L, 1f, null,
        false, false, false, false, false, emptyList(), emptyList(), emptyList(), emptyList(),
    )

    /**
     * Audiobooks and podcasts share one model: a book (or show) with a chapter (or episode), recent
     * items across the profile's apps, skip back/forward and speed. Books add bookmarks; podcasts
     * add the app's own extra buttons (e.g. Pocket Casts' star), minus ones the widget already has.
     */
    private fun itemsModel(ctx: Context, p: Profile, store: BookStore, setupTitle: String): Model {
        val source = Sources.byPkg(store.selected(ctx))?.takeIf { it in p.sources } ?: p.sources.first()
        if (!Media.hasAccess(ctx)) return setupModel(p, source, setupTitle)
        val c = Media.controllerFor(ctx, source.pkg)
        val recents = store.recents(ctx)
        val book = recents.firstOrNull { it.pkg == source.pkg }
        val playingBooks = Media.controllers(ctx, p).filter { Media.isPlaying(it) }
            .mapNotNull { pc -> recents.firstOrNull { it.pkg == pc.packageName } }
        val others = distinctBooks(recents, listOfNotNull(book) + playingBooks).mapIndexed { i, b ->
            Item(
                art = store.coverUri(ctx, b),
                title = b.title,
                sub = listOfNotNull(b.chapter, b.author).joinToString(" · "),
                pkg = b.pkg,
                tap = bookTap(ctx, store, b, 300 + p.ordinal * 20 + i),
            )
        }
        val speed = c?.let { Media.speed(it) } ?: 1f
        val canSetSpeed = c?.let { Media.supports(it, PlaybackState.ACTION_SET_PLAYBACK_SPEED) } ?: false
        return Model(
            profile = p,
            source = source,
            setupNeeded = false,
            hasSession = c != null,
            playing = c?.let { Media.isPlaying(it) } ?: false,
            title = book?.title ?: source.label,
            line2 = book?.chapter ?: if (book == null) "Tap to open ${source.label}" else null,
            line3 = book?.author,
            art = book?.let { store.coverUri(ctx, it) },
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
            bookmarks = p == Profile.BOOKS,
            actions = if (p == Profile.PODCASTS) {
                c?.playbackState?.let { appActions(ctx, source.pkg, it) }.orEmpty().filterNot { duplicatesControls(it.label) }
            } else emptyList(),
            grid = others,
            rows = others,
            marks = if (p == Profile.BOOKS) book?.let { Bookmarks.forBook(ctx, it.key) }.orEmpty() else emptyList(),
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
    private fun distinctBooks(all: List<BookStore.Book>, current: List<BookStore.Book>): List<BookStore.Book> {
        fun norm(t: String?) = t.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        val excluded = current.flatMap { listOf(norm(it.title), norm(it.chapter)) }.filter { it.isNotEmpty() }
        val currentKeys = current.map { it.key }.toSet()
        fun isCurrent(b: BookStore.Book): Boolean {
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

        bindUpdate(ctx, rv)

        if (kind.text) {
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

    /** Update symbol when the Widget Updater reports a newer build. Tap: update. */
    private fun bindUpdate(ctx: Context, rv: RemoteViews) {
        val updater = if (UpdateStatus.available(ctx)) UpdateStatus.updaterIntent(ctx) else null
        if (updater != null) {
            rv.setViewVisibility(R.id.update, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.update, activity(ctx, 4, updater))
        } else {
            rv.setViewVisibility(R.id.update, View.GONE)
        }
    }

    // ---------------------------------------------------------------- Now Playing widget

    /**
     * Now Playing adapts to every size, like the other widgets: the latest item alone at 1×1, three
     * type buttons (music, audiobook, podcast) from 3×1, what's playing beside them from 4×1, and
     * recent items from all types when taller. Android picks the closest fitting layout.
     */
    private enum class MixKind(
        val layout: Int,
        val size: SizeF,
        val label: String,
        val single: Boolean = false,
        val buttons: Boolean = false,
        val info: Boolean = false,
        val sideLinks: Boolean = false,
        val recents: Int = 0,
        val recentRows: Boolean = false,
    ) {
        TILE(R.layout.widget_mix_tile, SizeF(50f, 50f), "latest item", single = true),
        STRIP_S(R.layout.widget_mix_strip_s, SizeF(150f, 50f), "latest item + info", single = true, info = true),
        ROW(R.layout.widget_mix, SizeF(220f, 50f), "music, audiobook, podcast buttons", buttons = true, sideLinks = true),
        ROW_INFO(R.layout.widget_mix_l, SizeF(320f, 50f), "3 buttons + what's playing", buttons = true, info = true),
        COLUMN(R.layout.widget_mix_v, SizeF(50f, 150f), "3 buttons stacked", buttons = true, sideLinks = true),
        SQUARE(R.layout.widget_mix_square, SizeF(150f, 150f), "3 buttons + what's playing, 2×2", buttons = true, info = true),
        TALL(R.layout.widget_mix_tall, SizeF(150f, 250f), "2×2 buttons + what's playing, recent grid",
            buttons = true, info = true, recents = 24),
        LARGE(R.layout.widget_mix_large, SizeF(260f, 150f), "buttons, what's playing, row of recent covers",
            buttons = true, info = true, recents = 7),
        XL(R.layout.widget_mix_xl, SizeF(260f, 250f), "buttons, what's playing, recent grid",
            buttons = true, info = true, recents = 56),
    }

    private val MIX = intArrayOf(R.id.mix1_play, R.id.mix2_play, R.id.mix3_play)
    private val MIX_ART = intArrayOf(R.id.mix1_art, R.id.mix2_art, R.id.mix3_art)
    /** Grid cell N's views (mrecN, mrecN_art); looked up by name since grids vary by size. */
    private val cellIds = mutableMapOf<String, Int>()
    private fun cellId(ctx: Context, name: String): Int =
        cellIds.getOrPut(name) { ctx.resources.getIdentifier(name, "id", ctx.packageName) }

    /** Left to right (or top to bottom): music, audiobook, podcast. */
    private val MIX_ORDER = listOf(Profile.MUSIC, Profile.BOOKS, Profile.PODCASTS)

    /** One item of any type, from any of its apps. */
    private class MixEntry(
        val profile: Profile,
        val key: String,
        val pkg: String,
        val title: String,
        val line2: String?,
        val line3: String?,
        val art: Uri?,
        val mediaId: String?,
        val lastPlayed: Long,
        /** What that app last had: tapping resumes it rather than searching. */
        val appCurrent: Boolean,
        /** Music recents are albums and mixes (never lone songs); set for those. */
        val group: MusicStore.Group? = null,
    )

    /** Every remembered item of every type, newest first. */
    private fun allEntries(ctx: Context): List<MixEntry> {
        // Music: the current song (for its button), then albums and mixes for the recent lists.
        val song = MusicStore.recents(ctx).firstOrNull()?.let { s ->
            MixEntry(Profile.MUSIC, "m:${s.key}", Sources.YTMUSIC.pkg, s.title, s.artist, s.album,
                MusicStore.coverUri(ctx, s), null, s.lastPlayed, true)
        }
        // Albums/mixes only if turned on: YouTube Music won't play them from a tap without opening.
        val groups = (if (MusicStore.inMixGrid(ctx)) MusicStore.otherGroups(ctx) else emptyList()).map { g ->
            MixEntry(Profile.MUSIC, "g:${g.key}", Sources.YTMUSIC.pkg, g.title, g.artist, null,
                MusicStore.coverUri(ctx, g.coverKey), null, g.lastPlayed, false, g)
        }
        val music = listOfNotNull(song) + groups
        fun items(p: Profile, store: BookStore) = store.recents(ctx).map { b ->
            MixEntry(p, "${p.name}:${b.key}", b.pkg, b.title, b.chapter, b.author, store.coverUri(ctx, b), b.mediaId,
                b.lastPlayed, store.current(ctx, b.pkg)?.key == b.key)
        }
        // Podcasts: one entry per show (the newest episode stands for it), never per episode.
        val shows = PodcastStore.recents(ctx).map { b ->
            // With the show reported as the album, title = show and chapter = episode; otherwise
            // title = episode and author = show.
            val show = if (b.chapter != null) b.title else (b.author ?: b.title)
            val episode = b.chapter ?: b.title.takeIf { it != show }
            MixEntry(Profile.PODCASTS, "PODCASTS:${show.lowercase()}", b.pkg, show, episode, null,
                PodcastStore.coverUri(ctx, b), b.mediaId, b.lastPlayed, PodcastStore.current(ctx, b.pkg)?.key == b.key)
        }.distinctBy { it.key }
        return (music + items(Profile.BOOKS, Store) + shows).sortedByDescending { it.lastPlayed }
    }

    /** What's playing now (any type), else whatever played last. */
    private fun nowEntry(ctx: Context, all: List<MixEntry>): MixEntry? {
        val playingPkgs = Media.controllers(ctx).filter { Media.isPlaying(it) }.map { it.packageName }.toSet()
        return all.firstOrNull { it.appCurrent && it.pkg in playingPkgs } ?: all.firstOrNull()
    }

    private fun buildMix(ctx: Context): RemoteViews {
        val all = if (Media.hasAccess(ctx)) allEntries(ctx) else emptyList()
        return RemoteViews(
            MixKind.values().associate { k ->
                k.size to fillMix(ctx, RemoteViews(ctx.packageName, k.layout), k, all)
            }
        )
    }

    /** Play/pause button for one item: pause or resume in place if its app runs, else ask the app to play it. */
    private fun setMixButton(ctx: Context, rv: RemoteViews, playId: Int, artId: Int, e: MixEntry?, p: Profile?, code: Int, setup: PendingIntent) {
        val c = e?.let { Media.controllerFor(ctx, it.pkg) }
        val playing = c != null && Media.isPlaying(c) && e?.appCurrent == true
        if (e?.art != null) rv.setImageViewUri(artId, e.art)
        else rv.setImageViewResource(artId, R.drawable.art_placeholder)
        rv.setImageViewResource(playId, if (playing) R.drawable.ic_pause_big else R.drawable.ic_play_big)
        val label = (e?.profile ?: p)?.label ?: "Media"
        val what = e?.let { listOfNotNull(it.title, it.line2).joinToString(", ") } ?: "nothing yet"
        rv.setContentDescription(playId, "$label: $what. ${if (playing) "Pause" else "Play"}")
        val tap = when {
            !Media.hasAccess(ctx) -> setup
            e == null -> p?.let { openApp(ctx, it.sources.first()) } ?: setup
            c != null && e.appCurrent -> mixBroadcast(ctx, e.pkg)
            else -> mixPlayTap(ctx, e, code)
        }
        rv.setOnClickPendingIntent(playId, tap)
    }

    /** Ask the item's app to play it without opening (resume if it's what the app last had). */
    private fun mixPlayTap(ctx: Context, e: MixEntry, code: Int): PendingIntent {
        e.group?.let { g ->
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
            return playTap(ctx, code, e.pkg, false, g.mediaId, listOfNotNull(g.title, g.artist).joinToString(" "), search)
        }
        val search = Bundle().apply {
            if (e.profile == Profile.MUSIC) putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_TITLE, e.title)
            val by = if (e.profile == Profile.MUSIC) e.line2 else e.line3
            by?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        }
        val by = if (e.profile == Profile.MUSIC) e.line2 else e.line3
        return playTap(ctx, code, e.pkg, e.appCurrent, e.mediaId, listOfNotNull(e.title, by).joinToString(" "), search)
    }

    private fun fillMix(ctx: Context, rv: RemoteViews, k: MixKind, all: List<MixEntry>): RemoteViews {
        val setup = activity(ctx, 1, Intent(ctx, MainActivity::class.java))
        val now = nowEntry(ctx, all)

        if (k.single) setMixButton(ctx, rv, R.id.mix0_play, R.id.mix0_art, now, null, 400, setup)

        // The three type buttons: the latest item of each type.
        val shown = mutableSetOf<String>()
        if (k.buttons) {
            for ((i, p) in MIX_ORDER.withIndex()) {
                val e = all.firstOrNull { it.profile == p && it.group == null }
                e?.let { shown.add(it.key) }
                setMixButton(ctx, rv, MIX[i], MIX_ART[i], e, p, 401 + i, setup)
            }
        } else {
            now?.let { shown.add(it.key) }
        }

        if (k.info) {
            val open = now?.let { e -> Sources.byPkg(e.pkg)?.let { openApp(ctx, it) } } ?: setup
            rv.setOnClickPendingIntent(R.id.info, if (Media.hasAccess(ctx)) open else setup)
            rv.setTextViewText(
                R.id.title,
                now?.title ?: if (Media.hasAccess(ctx)) "Nothing played yet" else "Set up Now Playing",
            )
            textOrGone(rv, R.id.line2, now?.line2 ?: if (!Media.hasAccess(ctx)) "Tap to allow access" else null)
            textOrGone(rv, R.id.line3, now?.line3)
            // One small action for what's playing: bookmark (audiobooks) or +30s (podcasts).
            val c = now?.takeIf { it.appCurrent }?.let { Media.controllerFor(ctx, it.pkg) }
            val pkg = now?.pkg
            if (c != null && pkg != null && now?.profile == Profile.BOOKS) {
                rv.setViewVisibility(R.id.info_bookmark, View.VISIBLE)
                rv.setOnClickPendingIntent(R.id.info_bookmark, mixBroadcast(ctx, pkg, NowPlayingWidget.ACTION_BOOKMARK_APP))
            } else {
                rv.setViewVisibility(R.id.info_bookmark, View.GONE)
            }
            if (c != null && pkg != null && now?.profile == Profile.PODCASTS) {
                rv.setViewVisibility(R.id.info_plus30, View.VISIBLE)
                rv.setOnClickPendingIntent(R.id.info_plus30, mixBroadcast(ctx, pkg, NowPlayingWidget.ACTION_PLUS30_APP))
            } else {
                rv.setViewVisibility(R.id.info_plus30, View.GONE)
            }
        }

        // Recent covers from every type (albums/mixes, podcast shows, books), newest first, no words.
        // Empty cells stay blank so the grid keeps its shape.
        if (k.recents > 0) {
            val rest = all.filter { it.key !in shown }
            for (i in 0 until k.recents) {
                val cell = cellId(ctx, "mrec${i + 1}")
                val art = cellId(ctx, "mrec${i + 1}_art")
                val e = rest.getOrNull(i)
                if (e == null) {
                    rv.setViewVisibility(cell, View.INVISIBLE)
                    continue
                }
                rv.setViewVisibility(cell, View.VISIBLE)
                if (e.art != null) rv.setImageViewUri(art, e.art)
                else rv.setImageViewResource(art, R.drawable.art_placeholder)
                rv.setContentDescription(art, listOfNotNull(e.profile.label, e.title).joinToString(": "))
                rv.setOnClickPendingIntent(cell, mixPlayTap(ctx, e, 500 + i))
            }
        }

        bindUpdate(ctx, rv)
        return rv
    }

    /** Same rule Android uses to pick a layout (for the setup screen's size list). */
    private fun bestFitMix(w: Float, h: Float): MixKind =
        MixKind.values().filter { it.size.width <= w && it.size.height <= h }.minByOrNull {
            val dw = w - it.size.width
            val dh = h - it.size.height
            dw * dw + dh * dh
        } ?: MixKind.TILE

    private fun mixBroadcast(ctx: Context, pkg: String, action: String = NowPlayingWidget.ACTION_TOGGLE_APP): PendingIntent {
        val intent = Intent(ctx, NowPlayingWidget::class.java)
            .setAction(action)
            .setData(Uri.fromParts("mw", pkg, null))
        return PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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
    /** App buttons the widget already shows as its own controls (skip, rewind, speed…). */
    private fun duplicatesControls(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return listOf("skip", "rewind", "forward", "jump", "back", "speed", "seek").any { it in n }
    }

    private fun bookTap(ctx: Context, store: BookStore, b: BookStore.Book, code: Int): PendingIntent {
        val isAppCurrent = store.current(ctx, b.pkg)?.key == b.key
        val search = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_TITLE, b.title)
            b.author?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        }
        val query = listOfNotNull(b.title, b.author).joinToString(" ")
        return playTap(ctx, code, b.pkg, isAppCurrent, b.mediaId, query, search)
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
        return playTap(ctx, 100 + index, Sources.YTMUSIC.pkg, false, g.mediaId, query, search)
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
