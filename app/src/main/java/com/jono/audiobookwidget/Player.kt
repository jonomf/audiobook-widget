package com.jono.audiobookwidget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.app.SearchManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.provider.MediaStore
import android.view.WindowManager
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
    /** How long to wait for the app to react before deciding it ignored the request. */
    private const val CONFIRM_MS = 4_000L

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
        Media.controllerFor(app, r.pkg)?.let { sendAndConfirm(app, it, r, done); return }

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
                sendAndConfirm(app, MediaController(app, token), r) { finish(it) }
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

    /**
     * Sends the request, then watches the app for a few seconds: some apps (YouTube Music) accept
     * requests from Assistant and Android Auto but silently ignore other callers. Only a visible
     * reaction (new item, buffering, starting to play) counts as success.
     */
    private fun sendAndConfirm(ctx: Context, c: MediaController, r: Request, done: (Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val title0 = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val wasPlaying = Media.isPlaying(c)
        var settled = false
        lateinit var cb: MediaController.Callback
        fun settle(ok: Boolean, how: String) {
            if (settled) return
            settled = true
            try { c.unregisterCallback(cb) } catch (e: Exception) { }
            log(ctx, "${r.pkg.substringAfterLast('.')}: ${r.label()} → $how")
            done(ok)
        }
        cb = object : MediaController.Callback() {
            override fun onMetadataChanged(m: MediaMetadata?) {
                val t = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
                if (t != null && t != title0) settle(true, "played \"$t\"")
            }

            override fun onPlaybackStateChanged(st: PlaybackState?) {
                when (st?.state) {
                    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
                    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> settle(true, "app started loading")
                    PlaybackState.STATE_PLAYING -> if (!wasPlaying || r.resume) settle(true, "app started playing")
                    PlaybackState.STATE_ERROR -> settle(false, "app error: ${st.errorMessage ?: "?"}")
                }
            }
        }
        c.registerCallback(cb, handler)
        val how = send(c, r)
        if (how == null) {
            settle(false, "app doesn't take this kind of request (actions ${c.playbackState?.actions})")
            return
        }
        // Resuming something already playing needs no reaction.
        if (r.resume && wasPlaying) { settle(true, "already playing"); return }
        handler.postDelayed({ settle(false, "$how sent, app ignored it") }, CONFIRM_MS)
    }

    private fun Request.label() = when {
        resume -> "resume"
        mediaId != null -> "id $mediaId"
        else -> "\"$query\""
    }

    private const val LOG_PREFS = "player_log"

    private fun log(ctx: Context, line: String) {
        val p = ctx.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE)
        val old = p.getString("log", "").orEmpty().lines().filter { it.isNotBlank() }
        p.edit().putString("log", (listOf(line) + old).take(6).joinToString("\n")).apply()
    }

    /** The last few play requests and what the app did with them (for the setup screen). */
    fun recentLog(ctx: Context): List<String> =
        ctx.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE).getString("log", "").orEmpty()
            .lines().filter { it.isNotBlank() }

    /** Returns what was sent, or null if the app supports none of the ways to ask. */
    private fun send(c: MediaController, r: Request): String? {
        val tc = c.transportControls
        val actions = c.playbackState?.actions
        // Apps that haven't reported their actions yet get the benefit of the doubt.
        fun allows(a: Long) = actions == null || (actions and a) != 0L
        return when {
            r.resume -> { tc.play(); "play" }
            r.mediaId != null && allows(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) -> { tc.playFromMediaId(r.mediaId, null); "play by id" }
            r.query.isNotEmpty() && allows(PlaybackState.ACTION_PLAY_FROM_SEARCH) -> { tc.playFromSearch(r.query, r.searchExtras); "search" }
            else -> null
        }
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
        // While we wait for the app to react, let touches fall through to the home screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        Player.play(this, request) { ok ->
            if (!ok) fallback(request)
            close()
        }
    }

    /**
     * The app ignored the background request. Next best: the same "play X" intent Assistant uses,
     * which the app handles itself (it may flash open); else just open the app.
     */
    private fun fallback(r: Player.Request) {
        // A YouTube video id: open YouTube Music straight on it, playing (not on a search page).
        val video = r.mediaId?.let { VIDEO_ID.find(it)?.value }
        if (r.pkg == Sources.YTMUSIC.pkg && video != null) {
            val watch = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/watch?v=$video"))
                .setPackage(r.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(watch)
                return
            } catch (e: Exception) { }
        }
        if (!r.resume && r.query.isNotEmpty()) {
            val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(r.pkg)
                putExtras(r.searchExtras)
                putExtra(SearchManager.QUERY, r.query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (search.resolveActivity(packageManager) != null) {
                try {
                    startActivity(search)
                    return
                } catch (e: Exception) { }
            }
        }
        packageManager.getLaunchIntentForPackage(r.pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        /** An 11-character YouTube video id, alone or inside a longer media id. */
        private val VIDEO_ID = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{11}(?![A-Za-z0-9_-])")
        const val EXTRA_PKG = "pkg"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_MEDIA_ID = "mediaId"
        const val EXTRA_QUERY = "query"
        const val EXTRA_SEARCH = "search"
    }
}
