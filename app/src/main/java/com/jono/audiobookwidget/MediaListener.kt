package com.jono.audiobookwidget

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Runs while notification access is granted. Watches Libby's and Audible's
 * media sessions and redraws the widget whenever what's playing changes.
 */
class MediaListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val tracked = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var lastSignature = ""

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> track(controllers.orEmpty()) }

    override fun onListenerConnected() {
        val msm = getSystemService(MediaSessionManager::class.java)
        sessionManager = msm
        val component = ComponentName(this, MediaListener::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            track(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Access was revoked between connecting and now.
        }
    }

    override fun onListenerDisconnected() = stopTracking()

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun stopTracking() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        tracked.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        tracked.clear()
        WidgetRenderer.renderAll(this)
    }

    private fun track(all: List<MediaController>) {
        val relevant = all.filter { Sources.isKnown(it.packageName) }
        val live = relevant.map { it.sessionToken }.toSet()

        tracked.keys.filter { it !in live }.forEach { token ->
            tracked.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }

        for (c in relevant) {
            if (c.sessionToken in tracked) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh(c)
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh(c)
                override fun onSessionDestroyed() = redraw()
            }
            c.registerCallback(cb, handler)
            tracked[c.sessionToken] = c to cb
            Store.capture(this, c)
        }
        redraw()
    }

    private fun refresh(c: MediaController) {
        val sig = Store.capture(this, c)
        if (sig != lastSignature) {
            lastSignature = sig
            WidgetRenderer.renderAll(this)
        }
    }

    private fun redraw() {
        lastSignature = ""
        WidgetRenderer.renderAll(this)
    }
}
