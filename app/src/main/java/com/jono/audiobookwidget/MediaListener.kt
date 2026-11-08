package com.jono.audiobookwidget

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import kotlin.math.abs

/**
 * Runs while notification access is granted. Watches every followed app's media session
 * (Libby, Audible, YouTube Music), saves what each is playing into its profile's store, and
 * redraws the widgets whenever that changes. Never reads notifications.
 */
class MediaListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val tracked = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val lastSignature = mutableMapOf<String, String>()
    private val lastBase = mutableMapOf<String, Long>()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> track(controllers.orEmpty()) }

    /** While something plays and the screen is on, redraw now and then so progress bars move. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!anyPlaying()) return
            if (getSystemService(PowerManager::class.java).isInteractive) WidgetRenderer.renderAll(this@MediaListener)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onListenerConnected() {
        val msm = getSystemService(MediaSessionManager::class.java)
        sessionManager = msm
        val component = ComponentName(this, MediaListener::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            track(msm.getActiveSessions(component))
            // Book apps that aren't running: ask them for their last-played book.
            RecentFetcher.fillGaps(this) { redraw() }
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
        handler.removeCallbacks(tick)
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
                override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
                    if (Profile.of(c.packageName) == Profile.MUSIC) MusicStore.forgetQueue(this@MediaListener)
                    refresh(c)
                }
                override fun onSessionDestroyed() = redraw()
            }
            c.registerCallback(cb, handler)
            tracked[c.sessionToken] = c to cb
            capture(c)
        }
        redraw()
    }

    private fun capture(c: MediaController): String = when (Profile.of(c.packageName)) {
        Profile.MUSIC -> MusicStore.capture(this, c)
        else -> Store.capture(this, c)
    }

    private fun refresh(c: MediaController) {
        val pkg = c.packageName
        val sig = capture(c)
        val base = Media.base(c)
        val previous = lastBase[pkg]
        val moved = base != null && (previous == null || abs(base - previous) > SEEK_MS)
        if (sig != lastSignature[pkg] || moved) {
            lastSignature[pkg] = sig
            if (base != null) lastBase[pkg] = base
            WidgetRenderer.renderAll(this)
        }
        scheduleTick()
    }

    private fun redraw() {
        lastSignature.clear()
        lastBase.clear()
        WidgetRenderer.renderAll(this)
        scheduleTick()
    }

    private fun anyPlaying() = tracked.values.any { (c, _) -> Media.isPlaying(c) }

    private fun scheduleTick() {
        if (!anyPlaying()) handler.removeCallbacks(tick)
        else if (!handler.hasCallbacks(tick)) handler.postDelayed(tick, TICK_MS)
    }

    private companion object {
        const val TICK_MS = 10_000L
        const val SEEK_MS = 2_000L
    }
}
