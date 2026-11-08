package com.jono.audiobookwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.widget.Toast

class AudiobookWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.renderAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val pkg = intent.data?.schemeSpecificPart
        when (intent.action) {
            ACTION_PLAY_PAUSE -> withController(ctx) { Media.togglePlay(it) }
            ACTION_BACK -> withController(ctx) { Media.skip(it, forward = false) }
            ACTION_FWD -> withController(ctx) { Media.skip(it, forward = true) }
            ACTION_PREV -> withController(ctx) { Media.chapter(it, forward = false) }
            ACTION_NEXT -> withController(ctx) { Media.chapter(it, forward = true) }
            ACTION_SPEED -> withController(ctx) { Media.cycleSpeed(it) }
            ACTION_BOOKMARK -> withController(ctx) { bookmark(ctx, it) }
            ACTION_JUMP -> withController(ctx) { jump(ctx, it, pkg?.toLongOrNull()) }
            ACTION_SELECT -> {
                if (pkg != null && Sources.isKnown(pkg)) Store.select(ctx, pkg)
                WidgetRenderer.renderAll(ctx)
            }
            ACTION_TOGGLE -> {
                Store.select(ctx, Sources.other(Store.selected(ctx)).pkg)
                WidgetRenderer.renderAll(ctx)
            }
        }
    }

    /** Saves a bookmark here, and in the app too if it offers a bookmark action. */
    private fun bookmark(ctx: Context, c: MediaController) {
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

    /** Jumps to a bookmark if its book is the one playing in that app. */
    private fun jump(ctx: Context, c: MediaController, createdAt: Long?) {
        val mark = createdAt?.let { Bookmarks.find(ctx, it) } ?: return
        if (Store.current(ctx, c.packageName)?.key != mark.bookKey) {
            Toast.makeText(ctx, "Start that book first, then tap the bookmark", Toast.LENGTH_LONG).show()
            return
        }
        c.transportControls.seekTo(mark.positionMs)
    }

    /** The listener service redraws once the app reacts; only redraw here if the session is gone. */
    private fun withController(ctx: Context, action: (MediaController) -> Unit) {
        val c = Media.controllerFor(ctx, Store.selected(ctx))
        if (c != null) action(c) else WidgetRenderer.renderAll(ctx)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.jono.audiobookwidget.PLAY_PAUSE"
        const val ACTION_BACK = "com.jono.audiobookwidget.BACK"
        const val ACTION_FWD = "com.jono.audiobookwidget.FWD"
        const val ACTION_SELECT = "com.jono.audiobookwidget.SELECT"
        const val ACTION_PREV = "com.jono.audiobookwidget.PREV"
        const val ACTION_NEXT = "com.jono.audiobookwidget.NEXT"
        const val ACTION_SPEED = "com.jono.audiobookwidget.SPEED"
        const val ACTION_BOOKMARK = "com.jono.audiobookwidget.BOOKMARK"
        const val ACTION_JUMP = "com.jono.audiobookwidget.JUMP"
        const val ACTION_TOGGLE = "com.jono.audiobookwidget.TOGGLE"
    }
}
