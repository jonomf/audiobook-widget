package com.jono.audiobookwidget

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.media.MediaBrowserService

/** Finds and drives the followed apps' media sessions. */
object Media {
    private const val SKIP_MS = 30_000L

    fun listenerComponent(ctx: Context) = ComponentName(ctx, MediaListener::class.java)

    fun hasAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(listenerComponent(ctx))

    /** Live sessions of the apps a profile follows (all known apps if null). */
    fun controllers(ctx: Context, profile: Profile? = null): List<MediaController> =
        allControllers(ctx).filter { c -> profile == null || profile.sources.any { it.pkg == c.packageName } }

    private fun allControllers(ctx: Context): List<MediaController> = try {
        ctx.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(listenerComponent(ctx))
            .filter { Sources.isKnown(it.packageName) }
    } catch (e: SecurityException) {
        emptyList()
    }

    /** The app's media browser service (what Android Auto and Assistant connect to), if it has one. */
    fun browserService(ctx: Context, pkg: String): ComponentName? =
        ctx.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }

    fun controllerFor(ctx: Context, pkg: String): MediaController? =
        controllers(ctx).firstOrNull { it.packageName == pkg }

    fun isPlaying(c: MediaController): Boolean = when (c.playbackState?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    fun duration(c: MediaController): Long =
        c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    /** The clock time the item would have started at; it only moves on a seek or speed change. */
    fun base(c: MediaController): Long? = positionMs(c)?.let { SystemClock.elapsedRealtime() - (it / speed(c)).toLong() }

    /** Runs one of the extra buttons the app offers (like, dislike, its own bookmark…). */
    fun runCustom(c: MediaController, action: String) {
        val custom = customActions(c).firstOrNull { it.action == action } ?: return
        c.transportControls.sendCustomAction(custom, custom.extras)
    }

    /** What plays after the current item, if the app shares its queue (next song, next chapter). */
    fun upNext(c: MediaController): String? {
        val queue = c.queue ?: return null
        val active = c.playbackState?.activeQueueItemId ?: return null
        val index = queue.indexOfFirst { it.queueId == active }
        if (index < 0) return null
        val next = queue.getOrNull(index + 1)?.description ?: return null
        val title = next.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val sub = next.subtitle?.toString()?.takeIf { it.isNotBlank() }
        return if (sub != null) "$title · ${MusicStore.cleanArtist(sub)}" else title
    }

    fun actions(c: MediaController): Long = c.playbackState?.actions ?: 0L

    fun supports(c: MediaController, action: Long) = (actions(c) and action) != 0L

    /** Playback speed; paused sessions often report 0, so treat that as 1×. */
    fun speed(c: MediaController): Float = c.playbackState?.playbackSpeed?.takeIf { it > 0f } ?: 1f

    /**
     * Real (wall-clock) time left in what the app reports as the current item, at the current
     * speed, or null if the app doesn't report a duration. Used for a live countdown.
     */
    fun remainingMs(c: MediaController): Long? {
        val st = c.playbackState ?: return null
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) return null
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (pos < 0L || pos > duration) return null
        return ((duration - pos) / speed(c)).toLong()
    }

    /** Current position on the app's timeline, accounting for time since the app last reported it. */
    fun positionMs(c: MediaController): Long? {
        val st = c.playbackState ?: return null
        if (st.position < 0L) return null
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return pos
    }

    /** Extra actions the app offers through its media session (like the buttons cars show). */
    fun customActions(c: MediaController): List<PlaybackState.CustomAction> =
        c.playbackState?.customActions.orEmpty()

    /** The app's own bookmark action, if it offers one. */
    fun bookmarkAction(c: MediaController): PlaybackState.CustomAction? =
        customActions(c).firstOrNull { "${it.action} ${it.name}".contains("bookmark", ignoreCase = true) }

    /** 1× → 1.25× → 1.5× → 1.75× → 2× → 1×. */
    fun cycleSpeed(c: MediaController) {
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f)
        val current = speed(c)
        val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds[0]
        c.transportControls.setPlaybackSpeed(next)
    }

    fun chapter(c: MediaController, forward: Boolean) {
        if (forward) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
    }

    fun togglePlay(c: MediaController) {
        if (isPlaying(c)) c.transportControls.pause() else c.transportControls.play()
    }

    /**
     * Prefer the app's own rewind / fast-forward, so the jump matches the skip
     * length set inside Libby or Audible. Otherwise seek 30 seconds.
     */
    fun skip(c: MediaController, forward: Boolean) {
        val st = c.playbackState
        val actions = st?.actions ?: 0L
        val tc = c.transportControls
        when {
            forward && (actions and PlaybackState.ACTION_FAST_FORWARD) != 0L -> tc.fastForward()
            !forward && (actions and PlaybackState.ACTION_REWIND) != 0L -> tc.rewind()
            st != null && (actions and PlaybackState.ACTION_SEEK_TO) != 0L -> {
                var pos = st.position
                if (st.state == PlaybackState.STATE_PLAYING) {
                    val elapsed = SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
                    pos += (elapsed * st.playbackSpeed).toLong()
                }
                val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                var target = (pos + if (forward) SKIP_MS else -SKIP_MS).coerceAtLeast(0L)
                if (duration > 0L) target = target.coerceAtMost(duration)
                tc.seekTo(target)
            }
            forward -> tc.fastForward()
            else -> tc.rewind()
        }
    }
}
