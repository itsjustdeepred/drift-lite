package com.eddyizm.tempus.helper

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drift: "Recognize song". Recognition runs on the phone ([com.eddyizm.tempus.lite.LiteRecognizer]); [resolve] then
 * looks the song up in the library (song id) and on Deezer (id Undertow can download).
 * [recognize] is the older Undertow path (POST /recognize), kept for servers that still expose it.
 */
object DriftRecognition {

    data class Song(
        val title: String, val artist: String, val album: String?, val cover: String?,
        val isrc: String?, val songId: String?, val deezerId: Long?,
    )

    sealed class Result {
        data class Found(val song: Song) : Result()
        object NotFound : Result()
        /** Recognition disabled on the server (404/403): enable it from the Undertow panel. */
        object Disabled : Result()
        data class Failed(val message: String) : Result()
    }

    // Drift: user-facing messages, from string resources
    private object Msg {
        // Null-safe: unit tests have no Application context
        private fun str(id: Int, vararg args: Any): String = com.eddyizm.tempus.App.getContext()?.getString(id, *args).orEmpty()
        val NOT_CONFIGURED get() = str(com.eddyizm.tempus.R.string.drift_rec_not_configured)
        val BAD_TOKEN get() = str(com.eddyizm.tempus.R.string.drift_rec_bad_token)
        val TOO_LARGE get() = str(com.eddyizm.tempus.R.string.drift_rec_too_large)
        val BAD_AUDIO get() = str(com.eddyizm.tempus.R.string.drift_rec_bad_audio)
        val PROVIDER get() = str(com.eddyizm.tempus.R.string.drift_rec_provider)
        fun http(code: Int) = str(com.eddyizm.tempus.R.string.drift_rec_http, code)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    /** Call off the main thread. [clip] is an audio file (M4A/AAC). */
    @JvmStatic
    fun recognize(clip: File): Result {
        if (!Undertow.enabled) return Result.Failed(Msg.NOT_CONFIGURED)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", clip.name, clip.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val res = try {
            http.newCall(Undertow.req("/recognize").post(body).build()).execute()
        } catch (e: Exception) {
            return Result.Failed(Undertow.diagnose(com.eddyizm.tempus.R.string.drift_what_recognition))
        }
        return res.use { r ->
            when {
                r.code == 404 || r.code == 403 -> Result.Disabled
                r.code == 401 -> Result.Failed(Msg.BAD_TOKEN)
                r.code == 413 -> Result.Failed(Msg.TOO_LARGE)
                !r.isSuccessful -> Result.Failed(Msg.http(r.code))
                else -> parse(r.body.string())
            }
        }
    }

    /** Library id and Deezer id for a song recognized on the phone. Call off the main thread. */
    @JvmStatic
    fun resolve(s: com.eddyizm.tempus.lite.LiteRecognizer.Song): Song {
        val norm = com.eddyizm.tempus.lite.LiteMatch::norm
        val title = norm(s.title)
        val artistWords = norm(s.artist).split(' ').filter { it.length > 1 }.toSet()
        fun sameArtist(a: String?) = a != null && norm(a).split(' ').any { it in artistWords }
        val songId = runCatching {
            com.eddyizm.tempus.App.getSubsonicClientInstance(false).searchingClient.search3(s.title, 20, 0, 0, 0, 0, 0)
                .execute().body()?.subsonicResponse?.searchResult3?.songs
                ?.firstOrNull { norm(it.title ?: "") == title && sameArtist(it.artist) }?.id
        }.getOrNull()
        val deezerId = runCatching {
            val q = okhttp3.HttpUrl.Builder().scheme("https").host("api.deezer.com").addPathSegments("search/track")
                .addQueryParameter("q", "artist:\"${s.artist}\" track:\"${s.title}\"").addQueryParameter("limit", "5").build()
            http.newCall(okhttp3.Request.Builder().url(q).build()).execute().use { r ->
                JsonParser.parseString(r.body.string()).asJsonObject.getAsJsonArray("data")?.map { it.asJsonObject }
                    ?.firstOrNull { norm(it.str("title") ?: "") == title }?.get("id")?.asLong
            }
        }.getOrNull()
        return Song(s.title, s.artist, s.album, s.cover, s.isrc, songId, deezerId)
    }

    internal fun parse(json: String): Result {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return Result.Failed(Msg.PROVIDER)
        return when (o.str("status")) {
            "found" -> Result.Found(Song(
                title = o.str("title") ?: "", artist = o.str("artist") ?: "", album = o.str("album"),
                cover = o.str("cover"), isrc = o.str("isrc"), songId = o.str("song_id"),
                deezerId = o.get("deezer_id")?.takeIf { !it.isJsonNull }?.asLong,
            ))
            "not_found" -> Result.NotFound
            else -> {
                val msg = o.str("message") ?: ""
                when {
                    msg.contains("disabled", ignoreCase = true) -> Result.Disabled
                    o.str("reason") == "bad_audio" -> Result.Failed(Msg.BAD_AUDIO)
                    else -> Result.Failed(Msg.PROVIDER)
                }
            }
        }
    }
}
