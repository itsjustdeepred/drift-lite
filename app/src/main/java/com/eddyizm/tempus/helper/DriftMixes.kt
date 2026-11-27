package com.eddyizm.tempus.helper

import android.util.Log
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.subsonic.models.Playlist
import com.eddyizm.tempus.util.Preferences
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Drift: the "made for you" mixes generated nightly by Undertow become the user's Navidrome
 * playlists ("Drift · Daily Mix 1", ...), so they also show up in Android Auto, on Sonos and elsewhere.
 * Credentials stay on the phone: the app writes the playlists.
 */
object DriftMixes {

    const val PREFIX = "Drift · "
    private const val TAG = "DriftMixes"
    private const val PREF_SYNCED = "drift_mixes_synced_at"

    data class MissingRelease(val deezerId: Long, val title: String, val artist: String, val date: String, val type: String?, val cover: String?)
    data class Mix(val key: String, val title: String, val subtitle: String, val trackIds: List<String>, val missing: List<MissingRelease>)
    data class Result(val generatedAt: Long, val mixes: List<Mix>, val kidsAlbums: Set<String> = emptySet(), val kidsArtists: Set<String> = emptySet())

    private const val PREF_KIDS_ALBUMS = "drift_kids_albums"
    private const val PREF_KIDS_ARTISTS = "drift_kids_artists"
    private const val PREF_KIDS_TRACKS = "drift_kids_tracks"

    /** Kids' albums and artists from the last /mixes, saved to filter the home right at startup. */
    @JvmStatic
    fun kidsAlbums(): Set<String> = App.getInstance().preferences.getStringSet(PREF_KIDS_ALBUMS, null).orEmpty()

    @JvmStatic
    fun kidsArtists(): Set<String> = App.getInstance().preferences.getStringSet(PREF_KIDS_ARTISTS, null).orEmpty()

    @JvmStatic
    fun kidsTracks(): Set<String> = App.getInstance().preferences.getStringSet(PREF_KIDS_TRACKS, null).orEmpty()

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val enabled get() = Undertow.enabled

    private fun req(path: String) = Undertow.req(path)

    /** Call off the main thread. */
    @JvmStatic
    fun fetch(): Result? {
        if (!enabled) return null
        return try {
            http.newCall(req("/mixes?user=" + java.net.URLEncoder.encode(Preferences.getUser().orEmpty(), "UTF-8")).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val o = JsonParser.parseString(r.body.string()).asJsonObject
                val mixes = o.getAsJsonArray("mixes").map { it.asJsonObject }.mapNotNull { m -> runCatching {
                    Mix(
                        m["key"].asString, m["title"].asString, m["subtitle"]?.asString ?: "",
                        m.getAsJsonArray("track_ids").map { it.asString },
                        (m.getAsJsonArray("missing") ?: com.google.gson.JsonArray()).map { it.asJsonObject }.mapNotNull { a -> runCatching {
                            MissingRelease(a["deezer_id"].asLong, a["title"].asString, a["artist"].asString,
                                a["release_date"]?.asString ?: "", a["type"]?.takeIf { !it.isJsonNull }?.asString,
                                a["cover"]?.takeIf { !it.isJsonNull }?.asString)
                        }.getOrNull() },
                    )
                }.getOrNull() }
                val kids = o.getAsJsonObject("kids")
                fun ids(k: String) = kids?.getAsJsonArray(k)?.mapNotNull { runCatching { it.asString }.getOrNull() }?.toSet().orEmpty()
                val res = Result(o["generated_at"].asLong, mixes, ids("albums"), ids("artists"))
                if (kids != null) {
                    App.getInstance().preferences.edit()
                        .putStringSet(PREF_KIDS_ALBUMS, res.kidsAlbums).putStringSet(PREF_KIDS_ARTISTS, res.kidsArtists)
                        .putStringSet(PREF_KIDS_TRACKS, ids("tracks")).apply()
                    DriftKids.invalidate()
                }
                res
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch", e); null
        }
    }

    /** Creates/updates the "Drift · ..." playlists on Navidrome if the mixes are newer than the last sync. */
    @JvmStatic
    @Synchronized  // two close onStart calls would create duplicate playlists
    fun sync(result: Result, force: Boolean = false): Boolean {
        val prefs = App.getInstance().preferences
        if (!force && prefs.getLong(PREF_SYNCED, 0) >= result.generatedAt) return false
        val client = App.getSubsonicClientInstance(false).playlistClient
        val me = Preferences.getUser()
        val existing: List<Playlist> = client.playlists.execute().body()?.subsonicResponse?.playlists?.playlists.orEmpty()
        var allOk = true
        for (mix in result.mixes) {
            if (mix.trackIds.isEmpty()) continue
            val name = PREFIX + mix.title
            val current = existing.firstOrNull { it.name == name && (it.owner == null || it.owner.equals(me, true)) }
            try {
                // createPlaylist with the id replaces the songs of the existing playlist
                if (!client.createPlaylist(current?.id, if (current == null) name else null, ArrayList(mix.trackIds)).execute().isSuccessful) allOk = false
            } catch (e: Exception) {
                Log.w(TAG, "sync ${mix.title}", e); allOk = false
            }
        }
        // if anything failed, retry on next launch instead of waiting for tomorrow's mixes
        if (allOk) prefs.edit().putLong(PREF_SYNCED, result.generatedAt).commit()
        return true
    }

    data class RemoteTrack(val id: Long, val title: String, val artist: String, val duration: Int, val explicit: Boolean)
    data class RemoteAlbum(val id: Long, val title: String, val artist: String, val cover: String?, val date: String,
                           val type: String?, val label: String?, val tracks: List<RemoteTrack>)

    @JvmStatic
    fun album(deezerId: Long): RemoteAlbum? = try {
        http.newCall(req("/album/$deezerId").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JsonParser.parseString(r.body.string()).asJsonObject
            fun str(k: String) = o[k]?.takeIf { !it.isJsonNull }?.asString
            RemoteAlbum(o["deezer_id"].asLong, o["title"].asString, o["artist"].asString, str("cover"), str("release_date") ?: "",
                str("type"), str("label"),
                o.getAsJsonArray("tracks").map { it.asJsonObject }.map {
                    RemoteTrack(it["deezer_id"].asLong, it["title"].asString, it["artist"].asString, it["duration"].asInt,
                        it["explicit"]?.asBoolean ?: false)
                })
        }
    } catch (e: Exception) {
        Log.w(TAG, "album", e); null
    }

    @JvmStatic
    fun requestTrack(deezerId: Long): Boolean = try {
        val body = """{"mode":"track","deezer_id":$deezerId}""".toRequestBody("application/json".toMediaType())
        http.newCall(req("/download").post(body).build()).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        Log.w(TAG, "track", e); false
    }

    data class Job(val deezerId: Long, val mode: String, val state: String, val detail: String, val progress: Float?, val quality: String?, val code: String? = null) {
        val active get() = state in setOf("queued", "searching", "downloading", "upgrading")
    }

    /** Latest downloads known to Undertow (most recent first). */
    @JvmStatic
    fun jobs(): List<Job> = try {
        http.newCall(req("/jobs").build()).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            JsonParser.parseString(r.body.string()).asJsonArray.map { it.asJsonObject }.map { j ->
                Job(j["deezer_id"].asLong, j["mode"].asString, j["state"].asString,
                    j["detail"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    j["progress"]?.takeIf { !it.isJsonNull }?.asFloat,
                    j["quality"]?.takeIf { !it.isJsonNull }?.asString,
                    j["code"]?.takeIf { !it.isJsonNull }?.asString)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "jobs", e); emptyList()
    }

    private fun s(id: Int, vararg args: Any): String = App.getContext().getString(id, *args)

    /** Short text for a download's status. */
    @JvmStatic
    fun statusText(j: Job): String = codeText(j.code, j.state, j.mode, j.progress, j.quality) ?: when (j.state) {
        "queued" -> s(R.string.drift_dl_queued)
        "searching" -> s(R.string.drift_dl_searching)
        "downloading" -> s(R.string.drift_dl_downloading) + (j.progress?.let { " · ${it.toInt()}%" } ?: "")
        "upgrading" -> s(R.string.drift_dl_upgrading, j.quality ?: "YouTube")
        "done" -> s(R.string.drift_dl_in_library) + (j.quality?.let { " · $it" } ?: "")
        "failed" -> s(R.string.drift_dl_failed)
        else -> j.state
    }

    /** Undertow status codes as localized text (the detail field comes in English). */
    @JvmStatic
    fun codeText(code: String?, state: String, mode: String, progress: Float?, quality: String?): String? {
        val pct = progress?.takeIf { it > 0 }?.let { " · ${it.toInt()}%" } ?: ""
        return when (code ?: return null) {
            "queued" -> s(R.string.drift_dl_queued)
            "done_existing" -> s(R.string.drift_dl_already_in_library)
            "yt_searching" -> s(R.string.drift_dl_yt_searching)
            "yt_downloading" -> s(R.string.drift_dl_yt_downloading) + pct
            "slskd_searching" -> s(R.string.drift_dl_slskd_searching)
            "slskd_upgrade_searching" -> s(R.string.drift_dl_upgrading, quality ?: "YouTube")
            "slskd_queued_remote" -> s(R.string.drift_dl_slskd_queued_remote)
            "slskd_downloading" -> (if (state == "upgrading") s(R.string.drift_dl_slskd_improving) else s(R.string.drift_dl_slskd_downloading)) + pct
            "lyrics_fetching" -> s(R.string.drift_dl_lyrics_fetching)
            "lidarr_lookup" -> s(R.string.drift_dl_album_lookup)
            "lidarr_searching" -> s(R.string.drift_dl_lidarr_searching)
            "lidarr_downloading" -> s(R.string.drift_dl_lidarr_downloading) + pct
            "lidarr_importing" -> s(R.string.drift_dl_lidarr_importing)
            "lidarr_import_blocked" -> s(R.string.drift_dl_lidarr_import_blocked)
            "lidarr_waiting_source" -> s(R.string.drift_dl_lidarr_waiting_source)
            "done" -> s(R.string.drift_dl_in_library) + (quality?.let { " · $it" } ?: "")
            "done_youtube_kept" -> s(R.string.drift_dl_youtube_kept)
            "done_upgrade_interrupted" -> s(R.string.drift_dl_upgrade_interrupted)
            "failed_no_source" -> s(R.string.drift_dl_no_source)
            "failed_not_found" -> s(R.string.drift_dl_not_found)
            "failed_lidarr_timeout" -> s(R.string.drift_dl_lidarr_timeout)
            "failed_interrupted" -> s(R.string.drift_dl_interrupted)
            "failed_error" -> s(R.string.drift_dl_failed)
            else -> null
        }
    }

    private val waves = android.util.LruCache<String, IntArray>(64)

    /** Song waveform (0..100 per bar), computed by the sidecar from the real file. Off the main thread. */
    @JvmStatic
    fun wave(songId: String): IntArray? {
        waves.get(songId)?.let { return it }
        if (!enabled) return null
        return try {
            http.newCall(req("/wave/" + java.net.URLEncoder.encode(songId, "UTF-8")).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                JsonParser.parseString(r.body.string()).asJsonArray.map { it.asInt }.toIntArray().also { waves.put(songId, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "wave", e); null
        }
    }

    /** Downloads a missing album with Lidarr via Undertow. */
    @JvmStatic
    fun requestAlbum(deezerId: Long): Boolean = try {
        val body = """{"mode":"album","deezer_id":$deezerId}""".toRequestBody("application/json".toMediaType())
        http.newCall(req("/download").post(body).build()).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        Log.w(TAG, "album", e); false
    }
}
