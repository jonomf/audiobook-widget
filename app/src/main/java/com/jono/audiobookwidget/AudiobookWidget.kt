package com.jono.audiobookwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.media.session.MediaController

class AudiobookWidget : AppWidgetProvider() {

    /** Runs when the widget is added, after a reboot, and every few hours: redraw and check for updates. */
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.renderAll(ctx)
        Updater.checkInBackground(ctx, goAsync()::finish)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val pkg = intent.data?.schemeSpecificPart
        when (intent.action) {
            ACTION_PLAY_PAUSE -> withController(ctx) { Media.togglePlay(it) }
            ACTION_BACK -> withController(ctx) { Media.skip(it, forward = false) }
            ACTION_FWD -> withController(ctx) { Media.skip(it, forward = true) }
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
        const val ACTION_TOGGLE = "com.jono.audiobookwidget.TOGGLE"
    }
}
