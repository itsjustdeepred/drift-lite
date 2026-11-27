package com.eddyizm.tempus.lite

import android.net.Uri
import android.util.Log
import com.eddyizm.tempus.App
import com.eddyizm.tempus.subsonic.models.Child
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Drift Lite: songs not in the library that come from YouTube. Each song has a stable id
 * "yt:<hash of artist+title>"; the index (title, artist, matched video, offline file) is on disk,
 * so the queue is restored even after a restart. The stream is resolved only when playback starts (ytm://).
 */
object LiteTracks {
    private const val TAG = "LiteTracks"
    const val PREFIX = "yt:"
    const val SCHEME = "ytm"

    data class T(val id: String, val title: String, val artist: String, val duration: Int, val cover: String?,
                 @Volatile var videoId: String? = null, @Volatile var file: String? = null, @Volatile var algo: Int = 0,
                 @Volatile var liked: Long = 0)

    private val ctx get() = App.getContext()
    private val dir get() = File(ctx.filesDir, "lite").apply { mkdirs() }
    private val indexFile get() = File(dir, "tracks.json")
    /** Folder of songs saved offline (app-private: removed on uninstall). */
    val musicDir: File get() = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, "music"), "Drift").apply { mkdirs() }

    private val gson = Gson()
    private val map: ConcurrentHashMap<String, T> by lazy { ConcurrentHashMap<String, T>().also { load(it) } }

    private fun load(m: ConcurrentHashMap<String, T>) {
        runCatching {
            val list: List<T?> = gson.fromJson(indexFile.readText(), object : TypeToken<List<T?>>() {}.type)
            list.filter { valid(it) }.forEach { m[it!!.id] = it }
        }
    }

    /** After a restore: re-reads the index; offline files that no longer exist are forgotten. */
    fun reload() {
        map.clear(); load(map)
        map.values.forEach { t -> if (t.file != null && !File(t.file!!).isFile) t.file = null }
        persist()
    }

    /** Index for the backup (without file paths, which are meaningless on another phone). */
    fun exportJson(): String = gson.toJson(map.values.map { it.copy(file = null) })
    fun importJson(json: String) {
        val list: List<T> = (gson.fromJson<List<T?>>(json, object : TypeToken<List<T?>>() {}.type) ?: emptyList()).filter { valid(it) }.map { it!! }
        list.forEach { t -> map[t.id] = t.copy(file = map[t.id]?.file, liked = maxOf(t.liked, map[t.id]?.liked ?: 0)) }
        persist()
    }

    @Synchronized
    private fun persist() {
        runCatching {
            val tmp = File(dir, "tracks.json.tmp")
            tmp.writeText(gson.toJson(map.values.toList()))
            tmp.renameTo(indexFile)
        }.onFailure { Log.w(TAG, "persist", it) }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun valid(t: T?) = t != null && t.id != null && t.title != null && t.artist != null && t.id.startsWith(PREFIX)

    @JvmStatic fun isLite(id: String?) = id != null && id.startsWith(PREFIX)

    fun idOf(title: String, artist: String): String {
        val h = MessageDigest.getInstance("SHA-1").digest((LiteMatch.norm(artist) + "|" + LiteMatch.norm(title)).toByteArray())
        return PREFIX + h.take(10).joinToString("") { "%02x".format(it) }
    }

    /** Registers (or finds) the song and returns its id. */
    @JvmOverloads
    fun register(title: String, artist: String, duration: Int, cover: String?, videoId: String? = null): String {
        val id = idOf(title, artist)
        val old = map[id]
        if (old == null || (old.cover == null && cover != null) || (old.videoId == null && videoId != null)) {
            map[id] = T(id, title, artist, if (duration > 0) duration else old?.duration ?: 0, cover ?: old?.cover, old?.videoId ?: videoId, old?.file,
                if (old?.videoId != null) old.algo else if (videoId != null) LiteYouTube.FIND_VERSION else 0)
            persist()
        }
        return id
    }

    /** Heart: liked songs stay on the phone ("Liked" list). */
    @JvmStatic
    fun setLiked(id: String, liked: Boolean) {
        val t = map[id] ?: return
        t.liked = if (liked) System.currentTimeMillis() else 0
        persist()
    }

    @JvmStatic fun isLiked(id: String) = (map[id]?.liked ?: 0) > 0

    fun liked(): List<T> = map.values.filter { it.liked > 0 }.sortedByDescending { it.liked }

    /** Songs saved on the phone, most recent first. */
    fun downloaded(): List<T> = map.values.filter { t -> t.file?.let { File(it).isFile } == true }
        .sortedByDescending { File(it.file!!).lastModified() }

    fun get(id: String): T? = map[id]

    fun isDownloaded(id: String): Boolean = map[id]?.file?.let { File(it).isFile } == true

    /** The song as a Child, to queue like any other. */
    @JvmStatic
    fun child(id: String): Child? {
        val t = map[id] ?: return null
        val file = t.file?.takeIf { File(it).isFile }
        return Child(id).apply {
            title = t.title; artist = t.artist; album = "YouTube"; duration = t.duration.takeIf { it > 0 }
            coverArtId = t.cover; path = file; suffix = file?.substringAfterLast('.')
            starred = if (t.liked > 0) java.util.Date(t.liked) else null
            isVideo = false
        }
    }

    /** Offline file if present, otherwise ytm://id ([resolve] resolves it when the player opens the stream). */
    @JvmStatic
    fun uri(id: String): Uri {
        map[id]?.file?.let { val f = File(it); if (f.isFile) return Uri.fromFile(f) }
        return Uri.Builder().scheme(SCHEME).authority(id.removePrefix(PREFIX)).build()
    }

    /** From ytm://hash to the real stream address (blocking, on the player thread). */
    @JvmStatic
    fun resolve(uri: Uri, fresh: Boolean = false): Uri {
        val id = PREFIX + uri.authority
        val a = audioFor(id, fresh) ?: throw java.io.IOException("Track not found on YouTube")
        return Uri.parse(a.url)
    }

    private fun videoFor(t: T): String? {
        // already saved offline: keep that file; otherwise search again if the rule changed
        t.videoId?.let { if (t.algo >= LiteYouTube.FIND_VERSION || t.file != null) return it }
        val v = LiteYouTube.find(t.title, t.artist, t.duration) ?: return null
        t.videoId = v
        t.algo = LiteYouTube.FIND_VERSION
        persist()
        return v
    }

    fun audioFor(id: String, fresh: Boolean = false, preferAac: Boolean = false): LiteYouTube.Audio? {
        val t = map[id] ?: return null
        val v = videoFor(t) ?: return null
        return LiteYouTube.audio(v, fresh, preferAac)
    }

    private val prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val prefetched = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Resolves the next songs in the queue (video + stream address) in the background, so a skip or a tap
     * on them plays at once. Offline songs and songs resolved in the last hours are left alone.
     */
    fun prefetchNext(player: androidx.media3.common.Player, count: Int = 3) {
        val start = player.currentMediaItemIndex
        if (start < 0) return
        val ids = (start until minOf(start + 1 + count, player.mediaItemCount))
            .map { player.getMediaItemAt(it).mediaId }
            .filter { isLite(it) && !isDownloaded(it) && prefetched.add(it) }
        if (prefetched.size > 200) synchronized(prefetched) { prefetched.clear() }
        ids.forEach { id -> prefetcher.execute { runCatching { audioFor(id) } } }
    }

    /** true if the song has a matching video (searches for it if needed). */
    fun findVideo(id: String): Boolean = map[id]?.let { videoFor(it) } != null

    private fun safe(s: String) = s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().take(80)

    /** Saves the song to the phone. [progress] 0..100. */
    fun download(id: String, cancelled: () -> Boolean = { false }, progress: (Float) -> Unit): Boolean {
        if (isDownloaded(id)) return true
        val t = map[id] ?: return false
        repeat(2) { attempt ->
            val a = audioFor(id, fresh = attempt > 0) ?: return false
            val out = File(musicDir, safe("${t.artist} - ${t.title}") + "." + a.ext)
            val part = File(out.path + ".part")
            try {
                LiteYouTube.http.newCall(okhttp3.Request.Builder().url(a.url).header("User-Agent", LiteYouTube.UA).build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    val total = r.body.contentLength()
                    var done = 0L
                    var last = 0L
                    r.body.byteStream().use { input ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled()) throw java.io.InterruptedIOException("cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                if (total > 0 && done - last > 128 * 1024) { last = done; progress(done * 100f / total) }
                            }
                        }
                    }
                }
                if (!part.renameTo(out)) return false
                t.file = out.path
                persist()
                progress(100f)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "download $id", e)
                part.delete()
                if (cancelled()) return false
            }
        }
        return false
    }

    fun delete(id: String) {
        val t = map[id] ?: return
        t.file?.let { File(it).delete() }
        t.file = null
        persist()
    }
}
