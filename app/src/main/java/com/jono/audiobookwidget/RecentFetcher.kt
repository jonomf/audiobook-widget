package com.jono.audiobookwidget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService

/**
 * Books only. Asks Libby / Audible for their last-played book even when they aren't running, the same way
 * Android's "resume playback" cards do (a media browser connection asking for the recent root).
 * Best effort: an app may refuse callers it doesn't know, in which case nothing changes.
 * Only fills gaps, i.e. apps with no book in the recents list yet.
 */
object RecentFetcher {
    private const val TIMEOUT_MS = 8_000L
    private val inFlight = mutableSetOf<String>()

    /** Main thread. Calls [onChange] after any app's book was added. */
    fun fillGaps(ctx: Context, onChange: () -> Unit) {
        val app = ctx.applicationContext
        for (src in Profile.BOOKS.sources) {
            if (Store.current(app, src.pkg) != null || src.pkg in inFlight) continue
            fetch(app, src.pkg) { added -> if (added) onChange() }
        }
    }

    private fun fetch(ctx: Context, pkg: String, onDone: (Boolean) -> Unit) {
        val service = ctx.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo
        if (service == null) {
            onDone(false)
            return
        }
        inFlight.add(pkg)
        val handler = Handler(Looper.getMainLooper())
        var browser: MediaBrowser? = null
        var finished = false

        fun finish(added: Boolean) {
            if (finished) return
            finished = true
            inFlight.remove(pkg)
            try {
                browser?.disconnect()
            } catch (e: Exception) {
            }
            onDone(added)
        }

        handler.postDelayed({ finish(false) }, TIMEOUT_MS)
        val hints = Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) }
        val b0 = MediaBrowser(ctx, ComponentName(service.packageName, service.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return finish(false)
                try {
                    b.subscribe(b.root, object : MediaBrowser.SubscriptionCallback() {
                        override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                            val item = children.firstOrNull { it.isPlayable } ?: children.firstOrNull()
                            finish(item != null && add(ctx, pkg, item.description))
                        }

                        override fun onError(parentId: String) = finish(false)
                    })
                } catch (e: Exception) {
                    finish(false)
                }
            }

            override fun onConnectionFailed() = finish(false)
            override fun onConnectionSuspended() = finish(false)
        }, hints)
        browser = b0
        try {
            b0.connect()
        } catch (e: Exception) {
            finish(false)
        }
    }

    private fun add(ctx: Context, pkg: String, d: MediaDescription): Boolean {
        val title = d.title?.toString()?.takeIf { it.isNotBlank() } ?: return false
        val author = d.subtitle?.toString()?.takeIf { it.isNotBlank() }
        Store.addRecovered(ctx, pkg, title, author, art(ctx, d))
        return true
    }

    private fun art(ctx: Context, d: MediaDescription): Bitmap? {
        d.iconBitmap?.let { return it }
        val uri = d.iconUri ?: return null
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null // the app may not let others read its images
        }
    }
}
