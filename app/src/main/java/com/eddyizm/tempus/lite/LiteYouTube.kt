package com.eddyizm.tempus.lite

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Drift Lite: YouTube Music from the phone, no server (NewPipeExtractor). Looks the song up among YouTube
 * Music "songs" and returns the audio stream URL. All blocking: never on the main thread.
 */
object LiteYouTube {
    private const val TAG = "LiteYouTube"
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private object Dl : Downloader() {
        override fun execute(r: Request): Response {
            val b = okhttp3.Request.Builder().url(r.url()).method(r.httpMethod(), r.dataToSend()?.toRequestBody())
            r.headers().forEach { (k, v) -> v.forEach { b.addHeader(k, it) } }
            b.header("User-Agent", UA)
            http.newCall(b.build()).execute().use { resp ->
                if (resp.code == 429) throw ReCaptchaException("reCaptcha", r.url())
                return Response(resp.code, resp.message, resp.headers.toMultimap(), resp.body.string(), resp.request.url.toString())
            }
        }
    }

    @Volatile private var ready = false
    private fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val c = com.eddyizm.tempus.helper.DriftCountry.get()
            NewPipe.init(Dl, Localization(java.util.Locale.getDefault().language.ifEmpty { "en" }, c), ContentCountry(c))
            ready = true
        }
    }

    data class Audio(val url: String, val ext: String, val mime: String, val bitrate: Int)

    private val VIDEO_ID = Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
    private val audioCache = ConcurrentHashMap<String, Pair<Audio, Long>>()

    /** Matching rule version: videos found with an older rule are searched again. */
    const val FIND_VERSION = 2

    /**
     * id of the right YouTube Music video, or null. The title alone is not enough (searching "Numb Linkin Park"
     * YouTube lists a "Numb" by another artist first): the artist must match, the duration picks between versions.
     */
    fun find(title: String, artist: String, duration: Int): String? = try {
        init()
        val t = LiteMatch.norm(title)
        val a = LiteMatch.artists(artist)
        fun best(q: String): Pair<Double, StreamInfoItem>? {
            val s = ServiceList.YouTube.getSearchExtractor(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
            s.fetchPage()
            return s.initialPage.items.filterIsInstance<StreamInfoItem>().take(12).mapNotNull { i ->
                val up = LiteMatch.norm(i.uploaderName ?: "")
                val artistOk = up.isNotEmpty() && a.any { ar -> up == ar || up.contains(ar) || ar.contains(up) }
                if (!artistOk && a.isNotEmpty()) return@mapNotNull null
                val name = LiteMatch.norm(i.name)
                var score = when { name == t -> 6.0; name.startsWith(t) || t.startsWith(name) -> 3.0; name.contains(t) || t.contains(name) -> 1.5; else -> return@mapNotNull null }
                if (duration > 0 && i.duration > 0) {
                    val d = abs(i.duration - duration)
                    score += when { d <= 2 -> 4.0; d <= 6 -> 3.0; d <= 15 -> 1.0; d <= 40 -> -1.0; else -> -4.0 }
                }
                score to i
            }.maxByOrNull { it.first }
        }
        // one failed request (network, YouTube hiccup) must not lose the song: each query is tried on its own,
        // and the plain title is a last try (the artist check above still applies)
        fun safe(q: String) = runCatching { best(q) }.onFailure { Log.w(TAG, "query $q", it) }.getOrNull()
        val hit = safe("$title $artist")?.takeIf { it.first >= 6 } ?: safe("$artist $title") ?: safe(title)
            ?: run { Thread.sleep(800); safe("$title $artist") }
        hit?.second?.let { VIDEO_ID.find(it.url)?.groupValues?.get(1) }
    } catch (e: Exception) { Log.w(TAG, "find $artist - $title", e); null }

    data class Song(val videoId: String, val title: String, val artist: String, val duration: Int, val thumb: String?)

    /** Search among YouTube Music songs. */
    fun searchSongs(q: String): List<Song> = try {
        init()
        val s = ServiceList.YouTube.getSearchExtractor(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        s.fetchPage()
        s.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { i ->
            val id = VIDEO_ID.find(i.url)?.groupValues?.get(1) ?: return@mapNotNull null
            Song(id, i.name, i.uploaderName ?: "", i.duration.toInt(), i.thumbnails.maxByOrNull { it.width }?.url)
        }
    } catch (e: Exception) { Log.w(TAG, "search $q", e); emptyList() }

    /**
     * Best audio stream (usually Opus 160 kbps); the URL expires after a few hours.
     * [preferAac]: the AAC/M4A stream instead, for speakers that do not play Opus (Sonos, most DLNA).
     */
    fun audio(videoId: String, fresh: Boolean = false, preferAac: Boolean = false): Audio? {
        val key = if (preferAac) "$videoId:aac" else videoId
        audioCache[key]?.let { (a, t) -> if (!fresh && System.currentTimeMillis() - t < 3 * 3600_000L) return a }
        return try {
            init()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val all = info.audioStreams.filter { it.content.isNotEmpty() && it.isUrl }
            val pool = if (preferAac) all.filter { it.format?.suffix == "m4a" }.ifEmpty { all } else all
            val best = pool.maxByOrNull { it.averageBitrate } ?: return null
            val f = best.format
            Audio(best.content, f?.suffix ?: "webm", f?.mimeType ?: "audio/webm", best.averageBitrate).also {
                audioCache[key] = it to System.currentTimeMillis()
            }
        } catch (e: Exception) { Log.w(TAG, "audio $videoId", e); null }
    }
}

/** Drift Lite: normalization to compare titles and artists across Spotify/Deezer, YouTube and Navidrome. */
object LiteMatch {
    private val BRACKETS = Regex("""\s*[(\[][^)\]]*(feat|ft\.|with|remaster|version|versione|edit|live|mono|stereo|from|prod)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val DASH_SUFFIX = Regex("""\s+-\s+.*(remaster|version|edit|live|mix|from|radio).*$""", RegexOption.IGNORE_CASE)
    private val FEAT = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)

    fun norm(s: String): String {
        var x = BRACKETS.replace(s, "")
        x = DASH_SUFFIX.replace(x, "")
        x = FEAT.replace(x, "")
        x = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return x.replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    fun artists(s: String): List<String> =
        s.split(Regex(""",|&| x | e | and | feat\.? | ft\.? """, RegexOption.IGNORE_CASE)).map { norm(it) }.filter { it.isNotEmpty() }
}
