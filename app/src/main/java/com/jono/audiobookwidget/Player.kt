package com.jono.audiobookwidget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Plays a recent item (book, song, album, mix) in its app without opening the app, the way Android
 * Auto and Google Assistant do. If the app has a live media session, the request goes straight to
 * it. Otherwise this connects to the app's media browser service to get a session first.
 * Order of attempts: resume (if it's that app's current item) → play by the app's own id →
 * play by search. Reports false if the app couldn't be reached or supports none of those.
 */
object Player {
    private const val TIMEOUT_MS = 8_000L
    private const val LINGER_MS = 5_000L

    class Request(
        val pkg: String,
        /** Just resume: the item is what that app last had playing. */
        val resume: Boolean,
        val mediaId: String?,
        val query: String,
        val searchExtras: Bundle,
    )

    /** Main thread. [done] gets whether the request was handed to the app. */
    fun play(ctx: Context, r: Request, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        Media.controllerFor(app, r.pkg)?.let { done(send(it, r)); return }

        val service = Media.browserService(app, r.pkg)
        if (service == null) {
            done(false)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        var browser: MediaBrowser? = null
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            done(ok)
            // Stay connected a moment so the app can start playing before we let go.
            handler.postDelayed({ try { browser?.disconnect() } catch (e: Exception) { } }, LINGER_MS)
        }
        handler.postDelayed({ finish(false) }, TIMEOUT_MS)
        val b = MediaBrowser(app, service, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val token = browser?.sessionToken ?: return finish(false)
                finish(send(MediaController(app, token), r))
            }

            override fun onConnectionFailed() = finish(false)
            override fun onConnectionSuspended() = finish(false)
        }, null)
        browser = b
        try {
            b.connect()
        } catch (e: Exception) {
            finish(false)
        }
    }

    private fun send(c: MediaController, r: Request): Boolean {
        val tc = c.transportControls
        val actions = c.playbackState?.actions
        // Apps that haven't reported their actions yet get the benefit of the doubt.
        fun allows(a: Long) = actions == null || (actions and a) != 0L
        when {
            r.resume -> tc.play()
            r.mediaId != null && allows(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) -> tc.playFromMediaId(r.mediaId, null)
            allows(PlaybackState.ACTION_PLAY_FROM_SEARCH) -> tc.playFromSearch(r.query, r.searchExtras)
            else -> return false
        }
        return true
    }
}

/**
 * What a tap on a recent item opens: an invisible, instant screen that asks the app to play the
 * item, then closes, so you stay on the home screen. Only if the app refuses does it open the app.
 * (It's an activity rather than a background broadcast so that fallback is allowed to open the app.)
 */
class PlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val pkg = i.getStringExtra(EXTRA_PKG)
        if (pkg == null) {
            close()
            return
        }
        val request = Player.Request(
            pkg = pkg,
            resume = i.getBooleanExtra(EXTRA_RESUME, false),
            mediaId = i.getStringExtra(EXTRA_MEDIA_ID),
            query = i.getStringExtra(EXTRA_QUERY).orEmpty(),
            searchExtras = i.getBundleExtra(EXTRA_SEARCH) ?: Bundle(),
        )
        Player.play(this, request) { ok ->
            if (!ok) {
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                }
            }
            close()
        }
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_MEDIA_ID = "mediaId"
        const val EXTRA_QUERY = "query"
        const val EXTRA_SEARCH = "search"
    }
}
