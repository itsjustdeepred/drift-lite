package com.eddyizm.tempus.lite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drift Lite: the user's own Last.fm account. Sign-in happens on last.fm (the app never sees the password)
 * and gives a session key used to scrobble what is played and to read listening history, which drives
 * the "For you" recommendations (similar tracks and artists, minus what the user already knows).
 */
object LiteLastFm {
    private const val TAG = "LiteLastFm"
    private const val API = "https://ws.audioscrobbler.com/2.0/"
    const val CALLBACK = "driftlite://lastfm"
    const val JOIN_URL = "https://www.last.fm/join"
    private const val CACHE_MS = 12 * 3600_000L

    data class Rec(val id: String, val title: String, val artist: String, val cover: String?, val duration: Int, val because: String?)
    data class RecArtist(val name: String, val picture: String?, val ref: String?)
    data class ForYou(val tracks: List<Rec>, val artists: List<RecArtist>, val at: Long, val user: String)

    val available get() = BuildConfig.LASTFM_KEY.isNotEmpty() && BuildConfig.LASTFM_SECRET.isNotEmpty()
    private val prefs get() = App.getContext().getSharedPreferences("lite_lastfm", Context.MODE_PRIVATE)
    val user: String? get() = prefs.getString("user", null)
    private val session: String? get() = prefs.getString("session", null)
    val connected get() = available && session != null
    /** Signed-in user name, for screens that react to sign-in / sign-out. */
    val account = kotlinx.coroutines.flow.MutableStateFlow(if (available) prefs.getString("session", null)?.let { prefs.getString("user", null) } else null)
    var scrobbling: Boolean
        get() = prefs.getBoolean("scrobble", true)
        set(v) { prefs.edit().putBoolean("scrobble", v).apply() }

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val io = Executors.newSingleThreadExecutor()

    // ------------------------------------------------------------------ sign-in

    fun startLogin(ctx: Context) {
        val url = "https://www.last.fm/api/auth/?api_key=${BuildConfig.LASTFM_KEY}&cb=" + Uri.encode(CALLBACK)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Exchanges the token from the callback for a session; returns the user name. Call off the main thread. */
    fun finishLogin(token: String): String? {
        val s = call("auth.getSession", mapOf("token" to token), signed = true, post = false)?.asJsonObject?.getAsJsonObject("session") ?: return null
        val name = s.str("name") ?: return null
        val key = s.str("key") ?: return null
        prefs.edit().putString("user", name).putString("session", key).apply()
        cacheFile.delete()
        account.value = name
        return name
    }

    fun logout() {
        prefs.edit().remove("user").remove("session").apply()
        cacheFile.delete()
        account.value = null
    }

    // ------------------------------------------------------------------ scrobbling

    private val started = HashMap<String, Long>()

    /** Called by the player: [submission] false = now playing, true = the play counts. */
    @JvmStatic
    fun scrobble(item: MediaItem?, submission: Boolean) {
        if (item == null || !connected || !scrobbling) return
        val md = item.mediaMetadata
        val title = md.title?.toString()?.takeIf { it.isNotBlank() } ?: return
        val artist = md.artist?.toString()?.takeIf { it.isNotBlank() } ?: return
        val id = item.mediaId
        val dur = LiteTracks.get(id)?.duration ?: 0
        val now = System.currentTimeMillis() / 1000
        if (!submission) synchronized(started) { started[id] = now }
        val ts = synchronized(started) { started.remove(id.takeIf { submission } ?: "") } ?: (now - maxOf(30, dur / 2))
        io.execute {
            val p = mutableMapOf("artist" to artist, "track" to title)
            if (dur > 0) p["duration"] = dur.toString()
            if (submission) p["timestamp"] = ts.toString()
            val sent = call(if (submission) "track.scrobble" else "track.updateNowPlaying", p + ("sk" to session.orEmpty()), signed = true, post = true) != null
            if (submission && !sent) queue(p)
            if (sent) flushQueue()
        }
    }

    private val queueFile get() = File(File(App.getContext().filesDir, "lite").apply { mkdirs() }, "lastfm_queue.json")

    @Synchronized
    private fun queue(p: Map<String, String>) {
        val list = readQueue().toMutableList().apply { add(p) }.takeLast(500)
        runCatching { queueFile.writeText(gson.toJson(list)) }
    }

    private fun readQueue(): List<Map<String, String>> = runCatching {
        gson.fromJson<List<Map<String, String>>>(queueFile.readText(), object : TypeToken<List<Map<String, String>>>() {}.type)
    }.getOrNull().orEmpty()

    /** Sends scrobbles saved while offline, 50 at a time (the API limit). */
    @Synchronized
    private fun flushQueue() {
        var list = readQueue()
        while (list.isNotEmpty()) {
            val batch = list.take(50)
            val p = HashMap<String, String>()
            batch.forEachIndexed { i, s -> s.forEach { (k, v) -> p["$k[$i]"] = v } }
            if (call("track.scrobble", p + ("sk" to session.orEmpty()), signed = true, post = true) == null) break
            list = list.drop(50)
            runCatching { if (list.isEmpty()) queueFile.delete() else queueFile.writeText(gson.toJson(list)) }
        }
    }

    // ------------------------------------------------------------------ recommendations

    private val cacheFile get() = File(File(App.getContext().filesDir, "lite").apply { mkdirs() }, "lastfm_foryou.json")

    fun cached(): ForYou? = runCatching { gson.fromJson(cacheFile.readText(), ForYou::class.java) }.getOrNull()
        ?.takeIf { it.user == user && it.tracks != null && it.artists != null }

    /** Recommendations, from cache when recent. Null when not connected or Last.fm is unreachable. */
    suspend fun forYou(force: Boolean = false): ForYou? = withContext(Dispatchers.IO) {
        val u = user ?: return@withContext null
        if (!connected) return@withContext null
        cached()?.takeIf { !force && System.currentTimeMillis() - it.at < CACHE_MS }?.let { return@withContext it }
        build(u)?.also { runCatching { cacheFile.writeText(gson.toJson(it)) } } ?: cached()
    }

    private data class Seed(val artist: String, val title: String)

    private suspend fun build(u: String): ForYou? = coroutineScope {
        val gate = Semaphore(6)
        suspend fun <T> limited(f: () -> T): T = gate.withPermit { f() }

        val topTracksJ = async { limited { call("user.getTopTracks", mapOf("user" to u, "period" to "3month", "limit" to "30")) } }
        val recentJ = async { limited { call("user.getRecentTracks", mapOf("user" to u, "limit" to "50")) } }
        val topArtistsJ = async { limited { call("user.getTopArtists", mapOf("user" to u, "period" to "6month", "limit" to "50")) } }
        val topTracks = topTracksJ.await()?.obj("toptracks")?.list("track")?.mapNotNull { t -> seedOf(t, "artist") }
        val recent = recentJ.await()?.obj("recenttracks")?.list("track")?.mapNotNull { t -> seedOf(t, "artist") }
        val topArtists = topArtistsJ.await()?.obj("topartists")?.list("artist")?.mapNotNull { it.asJsonObject.str("name") }
        if (topTracks == null && recent == null && topArtists == null) return@coroutineScope null
        val liked = LiteTracks.liked().map { Seed(it.artist, it.title) }

        val heard = (topTracks.orEmpty() + recent.orEmpty() + liked).map { key(it.artist, it.title) }.toSet()
        val knownArtists = (topArtists.orEmpty() + LiteCatalog.followedArtists().map { it.name }).map { LiteMatch.norm(it) }.toSet()

        // similar tracks, weighted by how often each seed was played
        val seeds = (topTracks.orEmpty().take(8) + recent.orEmpty().take(6) + liked.take(6)).distinctBy { key(it.artist, it.title) }.take(16)
        val scores = HashMap<String, Pair<Double, Rec>>()
        seeds.map { s ->
            async { s to limited { call("track.getSimilar", mapOf("artist" to s.artist, "track" to s.title, "limit" to "15", "autocorrect" to "1")) } }
        }.awaitAll().forEach { (s, j) ->
            j?.obj("similartracks")?.list("track")?.forEach { e ->
                val o = e.asJsonObject
                val title = o.str("name") ?: return@forEach
                val artist = o.obj("artist")?.str("name") ?: return@forEach
                val k = key(artist, title)
                if (k in heard) return@forEach
                val m = o.str("match")?.toDoubleOrNull() ?: 0.1
                val old = scores[k]
                scores[k] = (old?.first ?: 0.0) + m to (old?.second ?: Rec("", title, artist, null, (o.str("duration")?.toIntOrNull() ?: 0), s.artist))
            }
        }
        val perArtist = HashMap<String, Int>()
        val picked = scores.values.sortedByDescending { it.first }.map { it.second }
            .filter { r -> (perArtist.merge(LiteMatch.norm(r.artist), 1, Int::plus) ?: 0) <= 2 }
            .take(40)
        // cover and exact length from the catalog, then the playable id
        val tracks = picked.map { r ->
            async {
                limited {
                    val hit = LiteCatalog.searchTracks("${r.artist} ${r.title}", 3).firstOrNull { LiteMatch.norm(it.title) == LiteMatch.norm(r.title) }
                    val dur = hit?.duration?.takeIf { it > 0 } ?: r.duration
                    val id = LiteTracks.register(r.title, r.artist, dur, hit?.cover)
                    r.copy(id = id, cover = hit?.cover, duration = dur)
                }
            }
        }.awaitAll()

        // similar artists to the most played ones, not already known
        val artistScores = HashMap<String, Pair<Double, String>>()
        topArtists.orEmpty().take(8).map { a ->
            async { limited { call("artist.getSimilar", mapOf("artist" to a, "limit" to "20", "autocorrect" to "1")) } }
        }.awaitAll().forEach { j ->
            j?.obj("similarartists")?.list("artist")?.forEach { e ->
                val name = e.asJsonObject.str("name") ?: return@forEach
                val n = LiteMatch.norm(name)
                if (n in knownArtists) return@forEach
                val m = e.asJsonObject.str("match")?.toDoubleOrNull() ?: 0.1
                artistScores[n] = (artistScores[n]?.first ?: 0.0) + m to name
            }
        }
        val artists = artistScores.values.sortedByDescending { it.first }.take(15).map { (_, name) ->
            async { limited { LiteCatalog.artistRefByName(name)?.let { (ref, hit) -> RecArtist(hit.name, hit.picture, ref) } ?: RecArtist(name, null, null) } }
        }.awaitAll().filter { it.ref != null }

        ForYou(tracks, artists, System.currentTimeMillis(), u)
    }

    private fun key(artist: String, title: String) = LiteMatch.norm(artist) + "|" + LiteMatch.norm(title)

    private fun seedOf(e: JsonElement, artistKey: String): Seed? {
        val o = e.asJsonObject
        val title = o.str("name") ?: return null
        val a = o.get(artistKey) ?: return null
        val artist = if (a.isJsonObject) a.asJsonObject.str("name") ?: a.asJsonObject.str("#text") else a.asString
        return artist?.takeIf { it.isNotBlank() }?.let { Seed(it, title) }
    }

    // ------------------------------------------------------------------ API plumbing

    private fun sign(p: Map<String, String>) = MessageDigest.getInstance("MD5")
        .digest((p.toSortedMap().entries.joinToString("") { it.key + it.value } + BuildConfig.LASTFM_SECRET).toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun call(method: String, params: Map<String, String>, signed: Boolean = false, post: Boolean = false): JsonElement? {
        if (!available) return null
        val p = params + mapOf("method" to method, "api_key" to BuildConfig.LASTFM_KEY)
        val all = if (signed) p + ("api_sig" to sign(p)) else p
        val req = if (post) {
            val b = FormBody.Builder().apply { all.forEach { (k, v) -> add(k, v) }; add("format", "json") }.build()
            Request.Builder().url(API).post(b)
        } else {
            val u = API.toHttpUrl().newBuilder().apply { all.forEach { (k, v) -> addQueryParameter(k, v) }; addQueryParameter("format", "json") }.build()
            Request.Builder().url(u)
        }.header("User-Agent", "DriftLite/${BuildConfig.VERSION_NAME}").build()
        return try {
            http.newCall(req).execute().use { r ->
                val j = JsonParser.parseString(r.body.string())
                if (j.isJsonObject && j.asJsonObject.has("error")) {
                    Log.w(TAG, "$method: ${j.asJsonObject.str("message")}")
                    if (j.asJsonObject.get("error").asInt == 9) logout() // session revoked on last.fm
                    null
                } else j
            }
        } catch (e: Exception) { Log.w(TAG, method, e); null }
    }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonElement.obj(k: String): JsonObject? = (this as? JsonObject)?.get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    // Last.fm returns a single object instead of a one-element array
    private fun JsonObject.list(k: String): List<JsonElement> = when (val v = get(k)) {
        null -> emptyList()
        else -> if (v.isJsonArray) v.asJsonArray.toList() else listOf(v)
    }
}
