package com.jono.audiobookwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.widget.Toast

/** The Audiobooks widget: Libby + Audible. (Class name kept so placed widgets survive updates.) */
class AudiobookWidget : MediaWidget(Profile.BOOKS)

/** The Music widget: YouTube Music. */
class MusicWidget : MediaWidget(Profile.MUSIC)

/** The Podcasts widget: Pocket Casts. */
class PodcastWidget : MediaWidget(Profile.PODCASTS)

/**
 * The Now Playing widget: one square button each for the latest music, audiobook and podcast.
 * Tapping a button whose app is running pauses or resumes it here; otherwise the button asks
 * the app to resume (via PlayActivity), so this provider only handles the toggle.
 */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.renderAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val pkg = intent.data?.schemeSpecificPart ?: return
        val c = Media.controllerFor(ctx, pkg)
        when (intent.action) {
            ACTION_TOGGLE_APP -> if (c != null) Media.togglePlay(c) else WidgetRenderer.renderAll(ctx)
            ACTION_BOOKMARK_APP -> if (c != null) MediaWidget.bookmark(ctx, c)
            ACTION_PLUS30_APP -> if (c != null) Media.seekBy(c, 30_000L)
        }
    }

    companion object {
        const val ACTION_TOGGLE_APP = "com.jono.audiobookwidget.TOGGLE_APP"
        const val ACTION_BOOKMARK_APP = "com.jono.audiobookwidget.BOOKMARK_APP"
        const val ACTION_PLUS30_APP = "com.jono.audiobookwidget.PLUS30_APP"
    }
}

/**
 * Shared widget provider: both widgets handle the same button taps, each against its own apps.
 * Drawing is in WidgetRenderer.
 */
abstract class MediaWidget(private val profile: Profile) : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.renderAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val extra = intent.data?.schemeSpecificPart?.takeIf { it != "-" }
        when (intent.action) {
            ACTION_PLAY_PAUSE -> withController(ctx) { Media.togglePlay(it) }
            ACTION_BACK -> withController(ctx) { Media.skip(it, forward = false) }
            ACTION_FWD -> withController(ctx) { Media.skip(it, forward = true) }
            ACTION_PREV -> withController(ctx) { it.transportControls.skipToPrevious() }
            ACTION_NEXT -> withController(ctx) { it.transportControls.skipToNext() }
            ACTION_SPEED -> withController(ctx) { Media.cycleSpeed(it) }
            ACTION_CUSTOM -> if (extra != null) withController(ctx) { Media.runCustom(it, extra) }
            ACTION_BOOKMARK -> withController(ctx) { bookmark(ctx, it) }
            ACTION_JUMP -> withController(ctx) { jump(ctx, it, extra?.toLongOrNull()) }
            ACTION_SELECT -> {
                if (extra != null && profile.sources.any { it.pkg == extra }) Store.select(ctx, extra)
                WidgetRenderer.renderAll(ctx)
            }
            ACTION_TOGGLE -> {
                val list = profile.sources
                val i = list.indexOfFirst { it.pkg == Store.selected(ctx) }
                Store.select(ctx, list[(i + 1).mod(list.size)].pkg)
                WidgetRenderer.renderAll(ctx)
            }
        }
    }

    /** The app this widget is showing right now. */
    private fun current(ctx: Context): MediaController? = when (profile) {
        Profile.BOOKS -> Media.controllerFor(ctx, Store.selected(ctx))
        Profile.MUSIC -> Media.controllerFor(ctx, Sources.YTMUSIC.pkg)
        Profile.PODCASTS -> Media.controllerFor(ctx, Sources.POCKETCASTS.pkg)
    }

    /** The listener service redraws once the app reacts; only redraw here if the session is gone. */
    private fun withController(ctx: Context, action: (MediaController) -> Unit) {
        val c = current(ctx)
        if (c != null) action(c) else WidgetRenderer.renderAll(ctx)
    }

    /** Jumps to a bookmark if its book is the one playing in that app. */
    private fun jump(ctx: Context, c: MediaController, createdAt: Long?) {
        val mark = createdAt?.let { Bookmarks.find(ctx, it) } ?: return
        if (Store.current(ctx, c.packageName)?.key != mark.bookKey) {
            Toast.makeText(ctx, "Start that book first, then tap the bookmark", Toast.LENGTH_LONG).show()
            return
        }
        c.transportControls.seekTo(mark.positionMs)
    }

    companion object {
        /** Saves a bookmark here, and in the app too if it offers a bookmark action. */
        fun bookmark(ctx: Context, c: MediaController) {
            val pos = Media.positionMs(c)
            if (pos == null) {
                Toast.makeText(ctx, "Couldn't bookmark: the app didn't report a position", Toast.LENGTH_LONG).show()
                return
            }
            val appAction = Media.bookmarkAction(c)
            appAction?.let { c.transportControls.sendCustomAction(it.action, null) }
            val book = Store.current(ctx, c.packageName)
            val source = Sources.byPkg(c.packageName)?.label ?: "the app"
            Bookmarks.add(
                ctx,
                Bookmarks.Mark(
                    bookKey = book?.key ?: "${c.packageName}|?",
                    chapter = book?.chapter,
                    positionMs = pos,
                    createdAt = System.currentTimeMillis(),
                    inApp = appAction != null,
                ),
            )
            val where = listOfNotNull(book?.chapter, Bookmarks.formatPosition(pos)).joinToString(" · ")
            val also = if (appAction != null) " (also in $source)" else ""
            Toast.makeText(ctx, "Bookmarked $where$also", Toast.LENGTH_SHORT).show()
            WidgetRenderer.renderAll(ctx)
        }

        const val ACTION_PLAY_PAUSE = "com.jono.audiobookwidget.PLAY_PAUSE"
        const val ACTION_BACK = "com.jono.audiobookwidget.BACK"
        const val ACTION_FWD = "com.jono.audiobookwidget.FWD"
        const val ACTION_PREV = "com.jono.audiobookwidget.PREV"
        const val ACTION_NEXT = "com.jono.audiobookwidget.NEXT"
        const val ACTION_SPEED = "com.jono.audiobookwidget.SPEED"
        const val ACTION_CUSTOM = "com.jono.audiobookwidget.CUSTOM"
        const val ACTION_BOOKMARK = "com.jono.audiobookwidget.BOOKMARK"
        const val ACTION_JUMP = "com.jono.audiobookwidget.JUMP"
        const val ACTION_SELECT = "com.jono.audiobookwidget.SELECT"
        const val ACTION_TOGGLE = "com.jono.audiobookwidget.TOGGLE"
    }
}
