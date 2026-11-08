package com.jono.audiobookwidget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from this repo's GitHub releases. Each push to main publishes a release
 * tagged build-N (N = GitHub run number = versionCode) with the APK attached.
 *
 * Reusable: copy Updater.kt and InstallReceiver.kt into another app, change REPO and ASSET,
 * and add the manifest bits (INTERNET, REQUEST_INSTALL_PACKAGES, InstallReceiver).
 */
object Updater {
    const val REPO = "jonomf/audiobook-widget"
    const val ASSET = "audiobook-widget.apk"
    const val TOKEN_PAGE = "https://github.com/settings/personal-access-tokens/new"

    data class Release(val build: Long, val assetId: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun token(ctx: Context): String? = prefs(ctx).getString("token", null)?.takeIf { it.isNotBlank() }

    fun setToken(ctx: Context, token: String?) {
        prefs(ctx).edit().putString("token", token?.trim()).remove("latest").remove("error").apply()
    }

    fun currentBuild(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun latestKnown(ctx: Context): Long = prefs(ctx).getLong("latest", 0L)

    fun lastError(ctx: Context): String? = prefs(ctx).getString("error", null)

    fun updateAvailable(ctx: Context): Boolean = latestKnown(ctx) > currentBuild(ctx)

    /** Checks GitHub off the main thread, then redraws the widget so the update dot appears. */
    fun checkInBackground(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (token(app) == null) {
            done?.invoke()
            return
        }
        Thread {
            try {
                val r = fetchLatest(app)
                prefs(app).edit().putLong("latest", r.build).remove("error").apply()
            } catch (e: Exception) {
                prefs(app).edit().putString("error", e.message ?: e.toString()).apply()
            } finally {
                WidgetRenderer.renderAll(app)
                done?.invoke()
            }
        }.start()
    }

    /** Blocking. Reads the latest release from the GitHub API. */
    fun fetchLatest(ctx: Context): Release {
        val conn = api(ctx, "https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json")
        try {
            when (val code = conn.responseCode) {
                200 -> {}
                401 -> throw IOException("GitHub didn't accept the token (expired or mistyped?)")
                403 -> throw IOException("The token can't read $REPO; give it Contents: Read-only")
                404 -> throw IOException("No release found, or the token doesn't include $REPO")
                else -> throw IOException("GitHub error $code")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val build = tag.removePrefix("build-").toLongOrNull()
                ?: throw IOException("Unexpected release tag \"$tag\"")
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == ASSET) {
                    prefs(ctx).edit().putLong("latest", build).apply()
                    return Release(build, a.getLong("id"))
                }
            }
            throw IOException("Release $tag has no $ASSET")
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking. Downloads the release's APK into the cache, reporting progress 0..1 (or -1 if unknown). */
    fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File {
        val conn = api(ctx, "https://api.github.com/repos/$REPO/releases/assets/${r.assetId}", "application/octet-stream")
        conn.instanceFollowRedirects = false
        val out = File(ctx.cacheDir, "update.apk")
        try {
            // GitHub answers with a redirect to a signed download URL, which must be fetched without the token.
            val (stream, length) = when (val code = conn.responseCode) {
                200 -> conn.inputStream to conn.contentLengthLong
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Download redirect had no URL")
                    val file = URL(location).openConnection() as HttpURLConnection
                    file.setRequestProperty("User-Agent", "android-updater")
                    file.connectTimeout = 20_000
                    file.readTimeout = 30_000
                    if (file.responseCode != 200) throw IOException("Download failed (${file.responseCode})")
                    file.inputStream to file.contentLengthLong
                }
                else -> throw IOException("Download failed ($code)")
            }
            copy(stream, out, length, progress)
        } finally {
            conn.disconnect()
        }
        return out
    }

    /** Hands the APK to Android's installer. InstallReceiver shows the confirm screen if one is needed. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // Once this app has installed itself, Android can skip the confirm screen.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                ctx, sessionId,
                Intent(ctx, InstallReceiver::class.java).setAction(InstallReceiver.ACTION_STATUS),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }

    private fun api(ctx: Context, url: String, accept: String): HttpURLConnection {
        val token = token(ctx) ?: throw IOException("No GitHub token yet")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "android-updater")
            connectTimeout = 20_000
            readTimeout = 30_000
        }
    }

    private fun copy(input: InputStream, out: File, length: Long, progress: (Float) -> Unit) {
        input.use { src ->
            out.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var lastReported = -1
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    total += n
                    val pct = if (length > 0) (total * 100 / length).toInt() else -1
                    if (pct != lastReported) {
                        lastReported = pct
                        progress(if (pct >= 0) pct / 100f else -1f)
                    }
                }
            }
        }
    }
}
