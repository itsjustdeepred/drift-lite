package com.eddyizm.tempus.lite

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.abs

/**
 * Drift Lite: synced lyrics from LRCLIB straight from the phone (same rules as Undertow:
 * at least 3 timed lines, last line within the duration, duration within 8 s, no instrumentals).
 */
object LiteLyrics {
    private const val TAG = "LiteLyrics"
    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val tried = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun valid(lrc: String?, duration: Int): Boolean {
        if (lrc.isNullOrBlank()) return false
        val stamps = STAMP.findAll(lrc).map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }.toList()
        if (stamps.size < 3) return false
        return duration <= 0 || stamps.last() <= duration + 2
    }

    private fun pick(o: JsonObject, duration: Int): String? {
        if (o["instrumental"]?.takeIf { !it.isJsonNull }?.asBoolean == true) return null
        val d = o["duration"]?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
        if (duration > 0 && d > 0 && abs(d - duration) > 8) return null
        val synced = o["syncedLyrics"]?.takeIf { !it.isJsonNull }?.asString
        if (valid(synced, duration)) return synced
        return o["plainLyrics"]?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
    }

    /** Lyrics (LRC if available, plain otherwise) or null. Once per track per session. Blocking. */
    @JvmStatic
    fun fetch(key: String, title: String?, artist: String?, duration: Int): String? {
        if (title.isNullOrBlank() || artist.isNullOrBlank() || !tried.add(key)) return null
        return try {
            val get = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist).addQueryParameter("track_name", title)
                .apply { if (duration > 0) addQueryParameter("duration", duration.toString()) }.build()
            call(get)?.let { JsonParser.parseString(it).asJsonObject }?.let { pick(it, duration) }?.let { return it }
            val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist).build()
            val list = call(search)?.let { JsonParser.parseString(it).asJsonArray }?.map { it.asJsonObject }.orEmpty()
            list.mapNotNull { pick(it, duration) }.maxByOrNull { if (STAMP.containsMatchIn(it)) 1 else 0 }
        } catch (e: Exception) { Log.w(TAG, "fetch $artist - $title", e); null }
    }

    private fun call(url: okhttp3.HttpUrl): String? =
        LiteYouTube.http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Drift (https://github.com/itsjustdeepred)").build())
            .execute().use { if (it.isSuccessful) it.body.string() else null }  // 404/400 = no lyrics
}
