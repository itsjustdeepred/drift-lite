package com.eddyizm.tempus.helper

import android.util.Log
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.Preferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drift: Spotify editorial playlists rebuilt from the library. The sidecar reads the playlist and reports
 * which songs are already there (Navidrome ids); missing ones are downloaded on request. All off the main thread.
 */
object DriftSpotify {
    private const val TAG = "DriftSpotify"
    const val PREFIX = "Ricomposta · "
    // "Drift · " is reserved for mixes written by Undertow, which deletes the ones it no longer generates
    private val OLD_PREFIXES = listOf("Spotify · ", "Drift · ")

    /** [group] is a stable key (G_*, or an Undertow group key); the title shown comes from [groupLabel]. */
    data class Entry(val id: String, val group: String, val name: String, val cover: String?, val count: Int,
                     val provider: String = "", val followed: Boolean = false, val sub: String? = null,
                     val groupTitle: String? = null)
    data class Track(val title: String, val artist: String, val duration: Int, val songId: String?)
    data class Playlist(val id: String, val name: String, val cover: String?, val tracks: List<Track>,
                        val followed: Boolean = false, val autoDownload: Boolean = false) {
        // Drift Lite: YouTube songs ("yt:...") can be played but are not in the library
        val inLibrary get() = tracks.count { it.songId != null && !com.eddyizm.tempus.lite.LiteTracks.isLite(it.songId) }
    }
    data class ItemState(val state: String, val progress: Float)
    data class Bulk(val running: Boolean, val total: Int, val done: Int, val queued: Int, val notFound: Int,
                    val active: Boolean = running, val progress: Float = 0f, val downloaded: Int = 0, val failed: Int = 0,
                    val items: Map<Int, ItemState> = emptyMap())

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val lite get() = BuildConfig.LITE
    @JvmStatic val enabled get() = lite || Undertow.enabled
    private fun req(path: String) = Undertow.req(path)
    /** Country of the phone (network/SIM, then system region): picks the local charts. */
    private fun country() = com.eddyizm.tempus.helper.DriftCountry.get()
    private fun str(o: JsonObject, k: String) = o[k]?.takeIf { !it.isJsonNull }?.asString

    // Catalog group keys (stable, also used by Android Auto); never compare against displayed titles
    const val G_RELEASES = "releases"
    const val G_FOR_YOU = "foryou"
    const val G_MINE = "mine"
    const val G_CHARTS = "charts"
    const val G_ADDED = "added"
    const val G_GENRES = "genres"
    const val G_DECADES = "decades"
    const val G_MOODS = "moods"
    const val G_FOLLOWED = "followed"
    const val G_ARTISTS = "artists"

    /** Display title of a catalog group; unknown Undertow keys fall back to the title sent by the server. */
    @JvmStatic
    fun groupLabel(ctx: android.content.Context, key: String, fallback: String? = null): String = when (key) {
        G_RELEASES -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_releases)
        G_FOR_YOU -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_for_you)
        // Drift Lite: "Your playlists" are the ones made on the phone; this group holds the saved charts, playlists and albums
        G_MINE -> ctx.getString(if (com.eddyizm.tempus.BuildConfig.LITE) com.eddyizm.tempus.R.string.lite_group_saved else com.eddyizm.tempus.R.string.drift_group_mine)
        G_CHARTS -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_charts)
        G_ADDED -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_added)
        G_GENRES -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_genres)
        G_DECADES -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_decades)
        G_MOODS -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_moods)
        G_FOLLOWED -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_followed)
        G_ARTISTS -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_artists)
        else -> fallback ?: key
    }

    @JvmStatic
    fun groupLabel(ctx: android.content.Context, e: Entry): String = groupLabel(ctx, e.group, e.groupTitle)

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8")

    private val cacheFile get() = java.io.File(App.getContext().filesDir, "drift_catalog_v2.json")

    /** Last catalog read, to show Home right away (refreshed afterwards). v2: groups are keys, not titles. */
    @JvmStatic
    fun cachedCatalog(): List<Entry>? = runCatching {
        @Suppress("SENSELESS_COMPARISON")
        com.google.gson.Gson().fromJson<List<Entry?>>(cacheFile.readText(), object : com.google.gson.reflect.TypeToken<List<Entry?>>() {}.type)
            .filter { it != null && it.id != null && it.group != null && it.name != null }.map { it!! }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    @JvmStatic
    fun catalog(): List<Entry>? = fetchCatalog()?.also { l -> if (l.isNotEmpty()) runCatching { cacheFile.writeText(com.google.gson.Gson().toJson(l)) } }

    private fun fetchCatalog(): List<Entry>? = if (lite) com.eddyizm.tempus.lite.LiteCatalog.catalog().takeIf { it.isNotEmpty() } else if (!enabled) null else try {
        http.newCall(req("/playlists?country=" + country()).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            JsonParser.parseString(r.body.string()).asJsonArray.map { it.asJsonObject }.map {
                Entry(it["id"].asString, it["group"].asString, it["name"].asString,
                    str(it, "cover"), it["count"].asInt, str(it, "provider").orEmpty(), it["followed"]?.asBoolean ?: false,
                    groupTitle = str(it, "group_title"))
            }
        }
    } catch (e: Exception) { Log.w(TAG, "catalog", e); null }

    @JvmStatic
    fun playlist(id: String): Playlist? = if (lite) com.eddyizm.tempus.lite.LiteCatalog.playlist(id) else try {
        http.newCall(req("/playlists/${enc(id)}").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JsonParser.parseString(r.body.string()).asJsonObject
            Playlist(o["id"].asString, o["name"].asString, str(o, "cover"), o.getAsJsonArray("tracks").map { it.asJsonObject }.map {
                Track(it["title"].asString, it["artist"].asString, it["duration"].asInt, str(it, "song_id"))
            }, o["followed"]?.asBoolean ?: false, o["auto_download"]?.asBoolean ?: false)
        }
    } catch (e: Exception) { Log.w(TAG, "playlist", e); null }

    /** Adds a playlist from a pasted link; returns the id or null. */
    @JvmStatic
    fun add(url: String): String? = if (lite) com.eddyizm.tempus.lite.LiteCatalog.add(url) else try {
        val body = JsonObject().apply { addProperty("url", url) }.toString().toRequestBody("application/json".toMediaType())
        http.newCall(req("/playlists").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) null else JsonParser.parseString(r.body.string()).asJsonObject["id"].asString
        }
    } catch (e: Exception) { Log.w(TAG, "add", e); null }

    private fun bulk(r: okhttp3.Response): Bulk? {
        if (!r.isSuccessful) return null
        val o = JsonParser.parseString(r.body.string()).asJsonObject
        val items = o.getAsJsonArray("tracks")?.associate { e ->
            val t = e.asJsonObject
            t["index"].asInt to ItemState(t["state"].asString, t["progress"].asFloat)
        }.orEmpty()
        return Bulk(o["running"].asBoolean, o["total"].asInt, o["done"].asInt, o["queued"].asInt, o["not_found"].asInt,
            o["active"]?.asBoolean ?: o["running"].asBoolean, o["progress"]?.asFloat ?: 0f,
            o["downloaded"]?.asInt ?: 0, o["failed"]?.asInt ?: 0, items)
    }

    @JvmStatic
    fun downloadMissing(id: String): Bulk? = if (lite) com.eddyizm.tempus.lite.LiteCatalog.download(id) else try {
        http.newCall(req("/playlists/${enc(id)}/download").post("".toRequestBody(null)).build()).execute().use { bulk(it) }
    } catch (e: Exception) { Log.w(TAG, "download", e); null }

    @JvmStatic
    fun downloadState(id: String): Bulk? = if (lite) com.eddyizm.tempus.lite.LiteCatalog.state(id) else try {
        http.newCall(req("/playlists/${enc(id)}/download").build()).execute().use { bulk(it) }
    } catch (e: Exception) { Log.w(TAG, "state", e); null }

    /** Follows the playlist: Undertow re-reads it every night (and with autoDownload downloads new songs). */
    @JvmStatic
    fun follow(id: String, autoDownload: Boolean): Boolean = if (lite) com.eddyizm.tempus.lite.LiteCatalog.follow(id, autoDownload) else try {
        val body = JsonObject().apply { addProperty("auto_download", autoDownload) }.toString().toRequestBody("application/json".toMediaType())
        http.newCall(req("/playlists/${enc(id)}/follow").post(body).build()).execute().use { it.isSuccessful }
    } catch (e: Exception) { Log.w(TAG, "follow", e); false }

    @JvmStatic
    fun unfollow(id: String): Boolean = if (lite) com.eddyizm.tempus.lite.LiteCatalog.unfollow(id) else try {
        http.newCall(req("/playlists/${enc(id)}/follow").delete().build()).execute().use { it.isSuccessful }
    } catch (e: Exception) { Log.w(TAG, "unfollow", e); false }

    /** Followed playlist, with how many new songs the last nightly update brought. */
    data class Followed(val entry: Entry, val inLibrary: Int, val lastSync: Long, val lastNew: Int) {
        /** "New" dot: new songs since the last time you opened it. */
        val hasNew get() = lastNew > 0 && lastSync > App.getInstance().preferences.getLong("drift_follow_seen_" + entry.id, 0)
    }

    @JvmStatic
    fun followed(): List<Followed>? = if (lite) com.eddyizm.tempus.lite.LiteCatalog.followed() else try {
        http.newCall(req("/follows").build()).execute().use { r ->
            if (!r.isSuccessful) null else JsonParser.parseString(r.body.string()).asJsonArray.map { it.asJsonObject }.map {
                val sync = it["last_sync"]?.takeIf { v -> !v.isJsonNull }?.let { v -> runCatching { v.asDouble.toLong() }.getOrNull()
                    ?: runCatching { java.time.OffsetDateTime.parse(v.asString).toEpochSecond() }.getOrNull() } ?: 0L
                Followed(
                    Entry(it["id"].asString, G_FOLLOWED, str(it, "name") ?: "", str(it, "cover"), it["count"]?.asInt ?: 0,
                        str(it, "provider").orEmpty(), true),
                    it["in_library"]?.asInt ?: 0, sync, it["last_new_tracks"]?.asInt ?: 0)
            }
        }
    } catch (e: Exception) { Log.w(TAG, "followed", e); null }

    /** Opened: the dot disappears until the next new songs. */
    @JvmStatic
    fun markSeen(id: String) {
        App.getInstance().preferences.edit().putLong("drift_follow_seen_$id", System.currentTimeMillis() / 1000 + 1).apply()
    }

    /** Ids of the followed playlists. */
    @JvmStatic
    fun follows(): List<String>? = if (lite) com.eddyizm.tempus.lite.LiteCatalog.follows() else try {
        http.newCall(req("/follows").build()).execute().use { r ->
            if (!r.isSuccessful) null else JsonParser.parseString(r.body.string()).asJsonArray.map { it.asJsonObject["id"].asString }
        }
    } catch (e: Exception) { Log.w(TAG, "follows", e); null }

    /**
     * Updates on Navidrome the followed playlists the user already saved ("Ricomposta · name"),
     * with the songs added since. Runs in the background once a day.
     */
    @JvmStatic
    fun syncSaved(): Int {
        if (lite) return com.eddyizm.tempus.lite.LiteCatalog.refreshFollows()
        val ids = follows() ?: return 0
        val client = App.getSubsonicClientInstance(false).playlistClient
        val mine = client.playlists.execute().body()?.subsonicResponse?.playlists?.playlists.orEmpty().map { it.name }.toSet()
        var n = 0
        for (id in ids) {
            val p = playlist(id) ?: continue
            if (PREFIX + p.name in mine || OLD_PREFIXES.any { it + p.name in mine }) { if (save(p)) n++ }
        }
        return n
    }

    const val KEY_OFFLINE = "drift_follow_offline"
    @JvmStatic val offlineEnabled get() = App.getInstance().preferences.getBoolean(KEY_OFFLINE, true)

    /**
     * Followed playlists on the phone too: downloads the library songs not there yet
     * (the download honors the "Wi-Fi only" setting). Off the main thread; [only] = a single playlist.
     */
    @JvmStatic
    fun downloadFollowed(context: android.content.Context, only: String? = null): Int {
        if (!offlineEnabled) return 0
        val ids = only?.let { listOf(it) } ?: follows() ?: return 0
        if (lite) return com.eddyizm.tempus.lite.LiteCatalog.downloadFollowed(context, only)
        var total = 0
        for (id in ids) {
            val p = playlist(id) ?: continue
            val songs = songs(p.tracks.mapNotNull { it.songId }.filterNot { com.eddyizm.tempus.lite.LiteTracks.isLite(it) }.distinct())
            if (songs.isEmpty()) continue
            total += songs.size
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                com.eddyizm.tempus.util.DownloadUtil.downloadMissing(context, songs, "undertow:$id", PREFIX + p.name) { }
            }
        }
        return total
    }

    private val pool = Executors.newFixedThreadPool(6)

    /** Library songs for the given ids, in the same order (getSong in parallel). */
    @JvmStatic
    fun songs(ids: List<String>): List<Child> {
        val client = App.getSubsonicClientInstance(false).browsingClient
        return ids.map { id -> pool.submit<Child?> {
            if (com.eddyizm.tempus.lite.LiteTracks.isLite(id)) return@submit com.eddyizm.tempus.lite.LiteTracks.child(id)
            runCatching { client.getSong(id).execute().body()?.subsonicResponse?.song }.getOrNull()
        } }.mapNotNull { runCatching { it.get(20, TimeUnit.SECONDS) }.getOrNull() }
    }

    /** Creates or updates the "Ricomposta · name" playlist on Navidrome with the songs you have. */
    @JvmStatic
    fun save(p: Playlist): Boolean = try {
        val client = App.getSubsonicClientInstance(false).playlistClient
        val name = PREFIX + p.name
        val me = Preferences.getUser()
        val current = client.playlists.execute().body()?.subsonicResponse?.playlists?.playlists.orEmpty()
            .firstOrNull { (it.name == name || OLD_PREFIXES.any { o -> it.name == o + p.name }) && (it.owner == null || it.owner.equals(me, true)) }
        val ids = ArrayList(p.tracks.mapNotNull { it.songId }.filterNot { com.eddyizm.tempus.lite.LiteTracks.isLite(it) }.distinct())
        client.createPlaylist(current?.id, if (current == null) name else null, ids).execute().isSuccessful
    } catch (e: Exception) { Log.w(TAG, "save", e); false }
}
