package com.jono.audiobookwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min

/**
 * Music side (YouTube Music): remembers recent songs (text and art) and the albums and mixes they
 * came from, so the Music widget still shows the last song after the app closes and can list other
 * recent songs and albums/mixes when there's room. Kept apart from the books Store: its own
 * preferences and its own cover files ("mcover_…", served as "m<id>").
 */
object MusicStore {
    private const val MAX_RECENTS = 8
    private const val MAX_GROUPS = 12
    private const val MAX_QUEUE_LOG = 6
    private const val GROUPS_VERSION = 2

    const val GROUP_MIX = "mix"
    const val GROUP_ALBUM = "album"

    private val MIX_WORD = Regex("\\b(super)?mix\\b", RegexOption.IGNORE_CASE)
    /** YouTube's automatic "Mix - <song>" queues, made just by starting a song. */
    private val AUTO_MIX = Regex("^mix\\s*[-–—:]", RegexOption.IGNORE_CASE)
    private val BRACKETED = Regex("[(\\[].*?[)\\]]")
    private val RELEASE_SUFFIX = Regex("\\s[-–—]\\s(ep|single)\\s*$")
    private val SPACES = Regex("\\s+")
    private val ARTIST_SEPARATOR = Regex(",|&| feat\\.?| ft\\.?| x ", RegexOption.IGNORE_CASE)

    /** Names apps give a queue when it isn't a real album or mix, so they can't identify one. */
    private val GENERIC_QUEUE_TITLES = setOf("up next", "queue", "your queue", "now playing")

    data class Song(
        val key: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val lastPlayed: Long,
    )

    /** An album or mix you've played; [coverKey] is the song whose art stands in for it. */
    data class Group(
        val key: String,
        val title: String,
        val artist: String?,
        val kind: String,
        val coverKey: String,
        val lastPlayed: Long,
        /** YouTube Music's own id to start it from (its first queue item, else the song), if it shared one. */
        val mediaId: String? = null,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music_state", Context.MODE_PRIVATE)

    /** Most recent first. The first entry is what's playing now. */
    fun groups(ctx: Context): List<Group> {
        val raw = prefs(ctx).getString("groups", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Group(
                    key = o.getString("key"),
                    title = o.getString("title"),
                    artist = o.optString("artist").takeIf { it.isNotBlank() },
                    kind = o.getString("kind"),
                    coverKey = o.getString("coverKey"),
                    lastPlayed = o.optLong("lastPlayed"),
                    mediaId = o.optString("mediaId").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The queue's own name if it has a meaningful one, such as a mix or playlist. */
    fun queueName(c: MediaController): String? = queueNameOf(c.queueTitle?.toString())

    private fun queueNameOf(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) !in GENERIC_QUEUE_TITLES }

    /** Lowercase, without "(Deluxe Edition)", "[Remastered]" or " - EP", so versions compare equal. */
    private fun norm(raw: String): String {
        val lower = raw.lowercase(Locale.US)
        val bare = lower.replace(BRACKETED, " ").replace(RELEASE_SUFFIX, " ").replace(SPACES, " ").trim()
        return bare.ifEmpty { lower.trim() }
    }

    /** "A, B feat. C" → "a". Tracks on one album often credit different guests. */
    private fun mainArtist(artist: String?): String =
        artist?.split(ARTIST_SEPARATOR)?.firstOrNull()?.trim()?.lowercase(Locale.US).orEmpty()

    /**
     * True only for a real mix (My Supermix, Discover Mix…). YouTube Music also names the queue
     * when you play one song or album, so a queue name alone doesn't make something a mix.
     */
    private fun isMix(queueName: String, song: Song): Boolean {
        if (!MIX_WORD.containsMatchIn(queueName) || AUTO_MIX.containsMatchIn(queueName)) return false
        val name = norm(queueName)
        val title = norm(song.title)
        return name != title && name != "$title mix" && (song.album == null || name != norm(song.album))
    }

    /** The album or mix this song belongs to. Null for a lone song or single. */
    private fun groupFor(song: Song, queueName: String?): Group? {
        if (queueName != null && isMix(queueName, song)) {
            return Group("mix|${norm(queueName)}", queueName, null, GROUP_MIX, song.key, song.lastPlayed)
        }
        val album = song.album ?: return null
        // A single reports its own title as the album.
        if (norm(album) == norm(song.title)) return null
        val key = "album|${norm(album)}|${mainArtist(song.artist)}"
        return Group(key, album, song.artist, GROUP_ALBUM, song.key, song.lastPlayed)
    }

    private fun rememberGroup(ctx: Context, song: Song, queueName: String?, playing: Boolean, startId: String?) {
        val fresh = groupFor(song, queueName)
        prefs(ctx).edit().putString("current_group", fresh?.key ?: "").apply()
        if (fresh == null) return

        val list = groups(ctx).toMutableList()
        val old = list.firstOrNull { it.key == fresh.key }
        val art = artHash(ctx, song.key)
        // Same album under a different credit: identical art, so keep just one entry.
        list.removeAll {
            it.key == fresh.key ||
                (art != null && fresh.kind == GROUP_ALBUM && it.kind == GROUP_ALBUM && artHash(ctx, it.coverKey) == art)
        }
        val keepCover = old != null && coverFile(ctx, coverId(old.coverKey)).exists()
        list.add(
            fresh.copy(
                coverKey = if (keepCover) old!!.coverKey else song.key,
                mediaId = startId ?: old?.mediaId,
                lastPlayed = if (playing || old == null) System.currentTimeMillis() else old.lastPlayed,
            )
        )
        list.sortByDescending { it.lastPlayed }
        saveGroups(ctx, list.take(MAX_GROUPS))
    }

    /**
     * Whether Now Playing's recent grid includes YouTube Music albums and mixes. Off by default:
     * YouTube Music ignores background play requests from other apps, so a tap can only open it.
     */
    fun inMixGrid(ctx: Context): Boolean = prefs(ctx).getBoolean("in_mix_grid", false)

    fun setInMixGrid(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("in_mix_grid", on).apply()
    }

    /** Other albums and mixes to show: not what's playing now, and no two with the same art. */
    fun otherGroups(ctx: Context): List<Group> {
        val current = prefs(ctx).getString("current_group", null)
        val seenArt = mutableSetOf<String>()
        recents(ctx).firstOrNull()?.let { song -> artHash(ctx, song.key)?.let { seenArt.add(it) } }
        return groups(ctx).filter { g ->
            g.key != current && (artHash(ctx, g.coverKey)?.let { seenArt.add(it) } ?: true)
        }
    }

    private val artHashes = HashMap<String, String>()

    /** A coarse fingerprint of a cover, so the same art under different names can be spotted. */
    private fun artHash(ctx: Context, songKey: String): String? {
        val id = coverId(songKey)
        artHashes[id]?.let { return it }
        val file = coverFile(ctx, id)
        if (!file.exists()) return null
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })
            ?: return null
        val small = Bitmap.createScaledBitmap(decoded, 8, 8, true)
        val hash = buildString {
            for (y in 0 until 8) for (x in 0 until 8) {
                val p = small.getPixel(x, y)
                append(Integer.toHexString((p shr 20) and 0xF))
                append(Integer.toHexString((p shr 12) and 0xF))
                append(Integer.toHexString((p shr 4) and 0xF))
            }
        }
        artHashes[id] = hash
        return hash
    }

    /** The last few queue names seen and how each was classified, for the setup screen. */
    /** For the setup screen: what the music grid has to work with, and why a song didn't count. */
    fun gridReport(ctx: Context): String {
        val song = recents(ctx).firstOrNull()
        val groups = groups(ctx)
        val others = otherGroups(ctx)
        val why = when {
            song == null -> "no song seen yet"
            song.album == null -> "YouTube Music didn't report an album for \"${song.title}\""
            norm(song.album) == norm(song.title) -> "\"${song.title}\" looks like a single (album = title)"
            else -> "album \"${song.album}\""
        }
        val names = groups.take(6).joinToString(", ") { "${it.title} (${it.kind})" }.ifEmpty { "none" }
        return "Last song: $why\n  Albums/mixes remembered: ${groups.size} ($names)\n" +
            "  Shown in grids (not the current one): ${others.size}" +
            (if (inMixGrid(ctx)) "" else " in the Music widget; Now Playing's grid: off (setting)")
    }

    fun queueLog(ctx: Context): List<String> {
        val raw = prefs(ctx).getString("queue_log", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun logQueueName(ctx: Context, name: String?, song: Song) {
        if (name.isNullOrBlank()) return
        val group = groupFor(song, queueNameOf(name))
        val entry = "$name → ${group?.kind ?: "neither"}"
        val log = queueLog(ctx)
        if (log.firstOrNull() == entry) return
        val updated = (listOf(entry) + log.filter { it != entry }).take(MAX_QUEUE_LOG)
        val arr = JSONArray()
        updated.forEach { arr.put(it) }
        prefs(ctx).edit().putString("queue_log", arr.toString()).apply()
    }

    private fun saveGroups(ctx: Context, list: List<Group>) {
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(JSONObject().apply {
                put("key", g.key)
                put("title", g.title)
                put("artist", g.artist ?: "")
                put("kind", g.kind)
                put("coverKey", g.coverKey)
                put("lastPlayed", g.lastPlayed)
                put("mediaId", g.mediaId ?: "")
            })
        }
        prefs(ctx).edit().putString("groups", arr.toString()).apply()
    }

    /** Most recent first. The first entry is the current song. */
    fun recents(ctx: Context): List<Song> {
        val raw = prefs(ctx).getString("recents", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Song(
                    key = o.getString("key"),
                    title = o.getString("title"),
                    artist = o.optString("artist").takeIf { it.isNotBlank() },
                    album = o.optString("album").takeIf { it.isNotBlank() },
                    lastPlayed = o.optLong("lastPlayed"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun upNext(ctx: Context): String? =
        prefs(ctx).getString("up_next", null)?.takeIf { it.isNotBlank() }

    fun forgetQueue(ctx: Context) {
        prefs(ctx).edit().remove("queue_key").apply()
    }

    /** Saves what the session is showing. Returns a signature so callers can skip redundant redraws. */
    fun capture(ctx: Context, c: MediaController): String {
        val p = prefs(ctx)
        val md = c.metadata
        val st = c.playbackState
        val playing = Media.isPlaying(c)
        var key = ""

        val title = md?.text(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (md != null && title != null) {
            val artist = (md.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))?.let { cleanArtist(it) }
            val album = md.text(MediaMetadata.METADATA_KEY_ALBUM)
            key = listOf(title, artist, album).joinToString("|")

            val cover = coverFile(ctx, coverId(key))
            if (!cover.exists()) {
                val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                if (bmp != null) saveArt(cover, bmp)
            }

            val list = recents(ctx).toMutableList()
            val old = list.firstOrNull { it.key == key }
            list.removeAll { it.key == key }
            val song = Song(
                key, title, artist, album,
                if (playing || old == null) System.currentTimeMillis() else old.lastPlayed,
            )
            list.add(song)
            list.sortByDescending { it.lastPlayed }

            // After an update to these rules: rebuild the grid from songs already remembered.
            if (p.getInt("groups_version", 0) < GROUPS_VERSION) {
                saveGroups(ctx, list.mapNotNull { groupFor(it, null) }.distinctBy { it.key })
                p.edit().putInt("groups_version", GROUPS_VERSION).apply()
            }
            // Its own id to start this album/mix again: the queue's first item when the queue is it.
            val startId = c.queue?.firstOrNull()?.description?.mediaId
                ?: md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            rememberGroup(ctx, song, queueName(c), playing, startId)
            logQueueName(ctx, c.queueTitle?.toString(), song)
            saveRecents(ctx, list.take(MAX_RECENTS))

            val queueKey = "$key#${st?.activeQueueItemId}"
            if (p.getString("queue_key", null) != queueKey) {
                p.edit().putString("up_next", Media.upNext(c) ?: "").putString("queue_key", queueKey).apply()
            }
        }

        return listOf(
            playing, st?.state, key, coverFile(ctx, coverId(key)).exists(), upNext(ctx),
            st?.customActions.orEmpty().joinToString(",") { "${it.action}:${it.icon}" },
        ).joinToString("|")
    }

    /** YouTube's auto-generated artist channels end in " - Topic". */
    fun cleanArtist(raw: String): String = raw.trim().removeSuffix(" - Topic")

    // --- Covers: one rounded PNG per song, served to the launcher by ArtProvider. ---

    fun coverId(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    fun coverFile(ctx: Context, id: String) = File(ctx.filesDir, "mcover_$id.png")

    /** Content URI for a song's art; the version changes with the file, so the launcher reloads it. */
    fun coverUri(ctx: Context, song: Song): Uri? = coverUri(ctx, song.key)

    fun coverUri(ctx: Context, songKey: String): Uri? {
        val id = coverId(songKey)
        val f = coverFile(ctx, id)
        if (!f.exists()) return null
        return Uri.parse("content://${ctx.packageName}.art/m$id/${f.lastModified()}")
    }

    private fun saveRecents(ctx: Context, list: List<Song>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("key", s.key)
                put("title", s.title)
                put("artist", s.artist ?: "")
                put("album", s.album ?: "")
                put("lastPlayed", s.lastPlayed)
            })
        }
        prefs(ctx).edit().putString("recents", arr.toString()).apply()
        // Drop art nothing uses any more: songs off this list and not standing in for an album or mix.
        val keep = (list.map { it.key } + groups(ctx).map { it.coverKey })
            .map { "mcover_${coverId(it)}.png" }.toSet()
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("mcover_") && it.name !in keep }?.forEach { it.delete() }
    }

    /** Square-crops (music videos send 16:9 art), scales and rounds the art once, when it arrives. */
    private fun saveArt(file: File, src: Bitmap) {
        val side = min(src.width, src.height)
        val outSide = min(side, 512)
        val scale = outSide.toFloat() / side
        val out = Bitmap.createBitmap(outSide, outSide, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setTranslate(-(src.width - side) / 2f, -(src.height - side) / 2f)
                postScale(scale, scale)
            })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val r = outSide * 0.08f
        Canvas(out).drawRoundRect(RectF(0f, 0f, outSide.toFloat(), outSide.toFloat()), r, r, paint)
        try {
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            file.delete()
        }
    }

    private fun MediaMetadata.text(key: String): String? = getString(key)?.takeIf { it.isNotBlank() }
}
