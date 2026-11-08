package com.jono.audiobookwidget

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock

/** Finds and drives Libby's / Audible's media sessions. */
object Media {
    private const val SKIP_MS = 30_000L

    fun listenerComponent(ctx: Context) = ComponentName(ctx, MediaListener::class.java)

    fun hasAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(listenerComponent(ctx))

    fun controllers(ctx: Context): List<MediaController> = try {
        ctx.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(listenerComponent(ctx))
            .filter { Sources.isKnown(it.packageName) }
    } catch (e: SecurityException) {
        emptyList()
    }

    fun controllerFor(ctx: Context, pkg: String): MediaController? =
        controllers(ctx).firstOrNull { it.packageName == pkg }

    fun isPlaying(c: MediaController): Boolean {
        val s = c.playbackState?.state ?: PlaybackState.STATE_NONE
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
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
