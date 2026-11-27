package com.eddyizm.tempus.lite

import android.content.Context
import android.util.Log
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.helper.DriftSpotify
import com.eddyizm.tempus.util.Preferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Drift Lite: charts and editorial playlists (Spotify, Deezer) read from the phone, matched against the
 * Navidrome library; songs you don't have become YouTube songs ("yt:..."), streamable and savable
 * offline. Followed playlists and matches live on the phone. Everything off the main thread.
 */
object LiteCatalog {
    private const val TAG = "LiteCatalog"
    private const val MAX_TRACKS = 100
    private val http get() = LiteYouTube.http
    private val gson = Gson()
    private val ctx get() = App.getContext()
    private val dir get() = File(ctx.filesDir, "lite").apply { mkdirs() }
    private val prefs get() = App.getInstance().preferences

    // ------------------------------------------------------------------ sources

    // Spotify Top 50 per country and Deezer "Top <Country>" (same tables as Undertow, verified 27/11/2026)
    private val SPOTIFY_TOP50 = mapOf(
        "IT" to "37i9dQZEVXbIQnj7RRhdSX", "US" to "37i9dQZEVXbLRQDuF5jeBp", "NL" to "37i9dQZEVXbKCF6dqVpDkS",
        "DE" to "37i9dQZEVXbJiZcmkrIHGU", "FR" to "37i9dQZEVXbIPWwFssbupI", "ES" to "37i9dQZEVXbNFJfN1Vw8d9",
        "GB" to "37i9dQZEVXbLnolsZ8PSNw", "BR" to "37i9dQZEVXbMXbN3EUUhlg", "MX" to "37i9dQZEVXbO3qyFxbkOE1",
        "CA" to "37i9dQZEVXbKj23U1GF4IR", "AU" to "37i9dQZEVXbJPcfkRz0wJ0", "SE" to "37i9dQZEVXbLoATJ81JYXz",
        "PT" to "37i9dQZEVXbKyJS56d1pgi", "BE" to "37i9dQZEVXbJNSeeHswcKB", "CH" to "37i9dQZEVXbJiyhoAPEfMK",
        "AT" to "37i9dQZEVXbKNHh6NIXu36", "PL" to "37i9dQZEVXbN6itCcaL3Tt", "JP" to "37i9dQZEVXbKXQ4mDTEBXq",
        "AR" to "37i9dQZEVXbMMy2roB9myp",
    )
    private const val SPOTIFY_GLOBAL = "37i9dQZEVXbMDoHDwVN2tF"
    private const val SPOTIFY_TODAYS_TOP_HITS = "37i9dQZF1DXcBWIGoYBM5M"
    private val DEEZER_TOP = mapOf(
        "AE" to 1362491345L, "AR" to 1279119721L, "AT" to 1313615765L, "AU" to 1313616925L, "BE" to 1266968331L, "BG" to 1362494565L,
        "BO" to 1362495515L, "BR" to 1111141961L, "CA" to 1652248171L, "CH" to 1313617925L, "CI" to 1362497945L, "CL" to 1279119121L,
        "CO" to 1116188451L, "CR" to 1313618455L, "CZ" to 1266969571L, "DE" to 1111143121L, "DK" to 1313618905L, "DZ" to 1362501015L,
        "EC" to 1362501235L, "EE" to 1221037201L, "EG" to 1362501615L, "ES" to 1116190041L, "FI" to 1221034071L, "FR" to 1109890291L,
        "GB" to 1111142221L, "GT" to 1279118671L, "HN" to 1116190301L, "HR" to 1266971131L, "HU" to 1362506695L, "ID" to 1116188761L,
        "IE" to 1313619455L, "IL" to 1362507345L, "IT" to 1116187241L, "JM" to 1362508575L, "JO" to 1362508765L, "JP" to 1362508955L,
        "KE" to 1362509215L, "KR" to 1362510315L, "LB" to 1362511155L, "LT" to 1221037371L, "LV" to 1221037511L, "MA" to 1362512715L,
        "MX" to 1111142361L, "MY" to 1362515675L, "NG" to 1362516565L, "NL" to 1266971851L, "NO" to 1313619885L, "PE" to 1362518525L,
        "PH" to 1362518895L, "PL" to 1266972311L, "PT" to 1362519755L, "PY" to 1362520135L, "RO" to 1279117071L, "RS" to 1266972981L,
        "SA" to 1362521285L, "SE" to 1313620305L, "SG" to 1313620765L, "SI" to 1362522355L, "SK" to 1266973701L, "SN" to 1362523075L,
        "SV" to 1362523615L, "TH" to 1362524475L, "TN" to 1362525375L, "TR" to 1116189071L, "UA" to 1362526495L, "US" to 1313621735L,
        "VE" to 1362527605L, "ZA" to 1362528775L,
    )
    private const val DEEZER_WORLDWIDE = 3155776842L
    private val GENRES = listOf("37i9dQZF1DX0XUsuxWHRQd", "37i9dQZF1DWXRqgorJj26U", "37i9dQZF1DX10zKzsJ2jva",
        "37i9dQZF1DWY7IeIP1cdjF", "37i9dQZF1DX4dyzvuaRJ0n", "37i9dQZF1DX76Wlfdnj7AP")
    private val DECADES = listOf("37i9dQZF1DX4UtSsGT1Sbe", "37i9dQZF1DXbTxeAdrVG2l", "37i9dQZF1DX4o1oenSJRJd")
    private val MOODS = listOf("37i9dQZF1DX4WYpdgoIcn6", "37i9dQZF1DX3rxVfibe1L0", "37i9dQZF1DWWMOmoXKqHTD",
        "37i9dQZF1DWZeKCadgRdKQ", "37i9dQZF1DX4sWSpwq3LiO", "37i9dQZF1DWWQRwui0ExPn")

    data class RawTrack(val title: String, val artist: String, val duration: Int, val cover: String?)
    data class Raw(val name: String, val cover: String?, val tracks: List<RawTrack>, val at: Long = System.currentTimeMillis())

    private const val SP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
    private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    private fun get(url: String, ua: String = SP_UA): String? =
        http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", ua).build()).execute().use { if (it.isSuccessful) it.body.string() else null }

    private fun str(o: JsonObject?, k: String) = o?.get(k)?.takeIf { !it.isJsonNull }?.asString

    private fun spotify(pid: String): Raw? {
        val html = get("https://open.spotify.com/embed/playlist/$pid") ?: return null
        val m = NEXT_DATA.find(html) ?: return null
        val e = JsonParser.parseString(m.groupValues[1]).asJsonObject["props"].asJsonObject["pageProps"].asJsonObject["state"]
            .asJsonObject["data"].asJsonObject["entity"].asJsonObject
        val cover = e.getAsJsonObject("coverArt")?.getAsJsonArray("sources")?.firstOrNull()?.asJsonObject?.let { str(it, "url") }
        val tracks = e.getAsJsonArray("trackList")?.map { it.asJsonObject }?.map {
            RawTrack(str(it, "title").orEmpty(), str(it, "subtitle").orEmpty().replace(' ', ' ').split(",").joinToString(", ") { a -> a.trim() },
                ((it["duration"]?.asLong ?: 0L) / 1000).toInt(), null)
        }.orEmpty()
        return Raw(str(e, "name") ?: str(e, "title") ?: "Playlist", cover, tracks.filter { it.title.isNotBlank() }.take(MAX_TRACKS))
    }

    private fun deezer(pid: String): Raw? {
        val d = get("https://api.deezer.com/playlist/$pid")?.let { JsonParser.parseString(it).asJsonObject } ?: return null
        if (d.has("error")) return null
        val tracks = mutableListOf<RawTrack>()
        var index = 0
        while (tracks.size < MAX_TRACKS) {
            val page = get("https://api.deezer.com/playlist/$pid/tracks?index=$index&limit=100")?.let { JsonParser.parseString(it).asJsonObject } ?: break
            val data = page.getAsJsonArray("data") ?: break
            data.map { it.asJsonObject }.forEach { t ->
                val title = str(t, "title") ?: return@forEach
                tracks += RawTrack(title, str(t.getAsJsonObject("artist"), "name").orEmpty(), t["duration"]?.asInt ?: 0,
                    t.getAsJsonObject("album")?.let { str(it, "cover_xl") ?: str(it, "cover_big") })
            }
            if (data.size() == 0 || !page.has("next")) break
            index += data.size()
        }
        return Raw(str(d, "title") ?: "Playlist", str(d, "picture_xl") ?: str(d, "picture_big"), tracks.take(MAX_TRACKS))
    }

    private fun deezerAlbum(pid: String): Raw? {
        val d = get("https://api.deezer.com/album/$pid")?.let { JsonParser.parseString(it).asJsonObject } ?: return null
        if (d.has("error")) return null
        val cover = str(d, "cover_xl") ?: str(d, "cover_big")
        val artist = str(d.getAsJsonObject("artist"), "name").orEmpty()
        val tracks = d.getAsJsonObject("tracks")?.getAsJsonArray("data")?.map { it.asJsonObject }?.mapNotNull { t ->
            RawTrack(str(t, "title") ?: return@mapNotNull null, str(t.getAsJsonObject("artist"), "name") ?: artist, t["duration"]?.asInt ?: 0, cover)
        }.orEmpty()
        return Raw(str(d, "title") ?: "Album", cover, tracks)
    }

    /** Artist: most played songs (Deezer), as a playlist. */
    private fun deezerArtist(pid: String): Raw? {
        val d = get("https://api.deezer.com/artist/$pid")?.let { JsonParser.parseString(it).asJsonObject } ?: return null
        if (d.has("error")) return null
        val top = get("https://api.deezer.com/artist/$pid/top?limit=50")?.let { JsonParser.parseString(it).asJsonObject }
        val tracks = top?.getAsJsonArray("data")?.map { it.asJsonObject }?.mapNotNull { t ->
            RawTrack(str(t, "title") ?: return@mapNotNull null, str(t.getAsJsonObject("artist"), "name").orEmpty(), t["duration"]?.asInt ?: 0,
                t.getAsJsonObject("album")?.let { str(it, "cover_xl") ?: str(it, "cover_big") })
        }.orEmpty()
        return Raw(str(d, "name") ?: "Artist", str(d, "picture_xl") ?: str(d, "picture_big"), tracks)
    }

    private val memo = ConcurrentHashMap<String, Raw>()
    private fun diskFile(ref: String) = File(dir, "pl_" + ref.replace(Regex("[^A-Za-z0-9]"), "_") + ".json")

    /** Saved copy of a playlist, or null if missing or unreadable (bad entries are dropped). */
    @Suppress("SENSELESS_COMPARISON")
    private fun readRaw(ref: String): Raw? = runCatching { gson.fromJson(diskFile(ref).readText(), Raw::class.java) }.getOrNull()
        ?.takeIf { it.name != null && it.tracks != null }
        ?.let { r -> r.copy(tracks = r.tracks.filter { t -> t != null && t.title != null && t.artist != null }) }
        ?.takeIf { it.tracks.isNotEmpty() }

    /** Raw playlist: memory (6 h), then network, and the last copy on disk when offline. */
    fun raw(ref: String, fresh: Boolean = false): Raw? {
        memo[ref]?.let { if (!fresh && System.currentTimeMillis() - it.at < 6 * 3600_000L) return it }
        // disk copy still fresh: no network (instant open)
        if (!fresh) readRaw(ref)
            ?.takeIf { System.currentTimeMillis() - it.at < 6 * 3600_000L && it.tracks.isNotEmpty() }?.let { memo[ref] = it; return it }
        val (provider, pid) = ref.substringBefore(':') to ref.substringAfter(':')
        val r = try {
            when (provider) { "spotify" -> spotify(pid); "deezer" -> deezer(pid); "dzalbum" -> deezerAlbum(pid); "dzartist" -> deezerArtist(pid); else -> null }
        } catch (e: Exception) { Log.w(TAG, "fetch $ref", e); null }
        if (r != null && r.tracks.isNotEmpty()) {
            memo[ref] = r
            runCatching { diskFile(ref).writeText(gson.toJson(r)) }
            return r
        }
        return memo[ref] ?: readRaw(ref)?.also { memo[ref] = it }
    }

    // ------------------------------------------------------------------ catalog

    private fun country() = com.eddyizm.tempus.helper.DriftCountry.get()

    private fun groups(): List<Pair<String, List<String>>> {
        val c = country()
        val charts = buildList {
            SPOTIFY_TOP50[c]?.let { add("spotify:$it") }
            DEEZER_TOP[c]?.let { add("deezer:$it") }
            add("spotify:$SPOTIFY_GLOBAL"); add("spotify:$SPOTIFY_TODAYS_TOP_HITS"); add("deezer:$DEEZER_WORLDWIDE")
        }
        val mine = loadFollows().keys.filter { !it.startsWith("dzartist:") }
        return listOf(DriftSpotify.G_FOR_YOU to forYou(), DriftSpotify.G_MINE to mine, DriftSpotify.G_CHARTS to charts,
            DriftSpotify.G_ADDED to added(), DriftSpotify.G_GENRES to GENRES.map { "spotify:$it" },
            DriftSpotify.G_DECADES to DECADES.map { "spotify:$it" }, DriftSpotify.G_MOODS to MOODS.map { "spotify:$it" })
    }

    private val pool = Executors.newFixedThreadPool(6)

    fun catalog(): List<DriftSpotify.Entry> {
        val follows = loadFollows()
        val releases = runCatching { releases() }.getOrDefault(emptyList())
        val todo = groups().flatMap { (g, refs) -> refs.map { g to it } }
        return releases + todo.map { (g, ref) -> pool.submit<DriftSpotify.Entry?> {
            raw(ref)?.let { DriftSpotify.Entry(ref, g, it.name, it.cover, it.tracks.size, ref.substringBefore(':'), ref in follows) }
        } }.mapNotNull { runCatching { it.get(40, TimeUnit.SECONDS) }.getOrNull() }
    }

    // ------------------------------------------------------------------ for you (genres chosen at first launch)

    private fun forYou(): List<String> {
        val key = "lite_for_you_" + LiteApp.genres.joinToString("_")
        prefs.getString(key, null)?.split('|')?.let { p ->
            if (p.size > 1 && System.currentTimeMillis() - p[0].toLong() < 12 * 3600_000L) return p.drop(1)
        }
        val out = LiteApp.genres.take(6).flatMap { g ->
            runCatching {
                get("https://api.deezer.com/chart/$g/playlists?limit=3")?.let { JsonParser.parseString(it).asJsonObject }
                    ?.getAsJsonArray("data")?.map { "deezer:" + it.asJsonObject["id"].asLong }
            }.getOrNull().orEmpty()
        }.distinct()
        if (out.isNotEmpty()) prefs.edit().putString(key, (listOf(System.currentTimeMillis().toString()) + out).joinToString("|")).apply()
        return out
    }

    // ------------------------------------------------------------------ followed artists and new releases

    data class Release(val albumId: Long, val title: String, val artist: String, val cover: String?, val date: String)
    private const val KEY_RELEASES = "lite_releases"
    private const val KEY_RELEASES_AT = "lite_releases_at"

    fun followedArtists(): List<DriftSpotify.Entry> = loadFollows().filterKeys { it.startsWith("dzartist:") }.map { (ref, f) ->
        DriftSpotify.Entry(ref, DriftSpotify.G_ARTISTS, f.name, f.cover, f.count, "deezer", true, sub = ctx.getString(R.string.lite_artist))
    }

    /** Releases from the last 60 days by followed artists (refreshed at most every 6 h), as catalog entries. */
    fun releases(fresh: Boolean = false): List<DriftSpotify.Entry> {
        val list = loadReleases(fresh)
        return list.map { r -> DriftSpotify.Entry("dzalbum:${r.albumId}", DriftSpotify.G_RELEASES, r.title, r.cover, 0, "deezer", false,
            sub = r.artist + " · " + r.date.split('-').reversed().joinToString("/")) }
    }

    private fun loadReleases(fresh: Boolean): List<Release> {
        @Suppress("SENSELESS_COMPARISON")
        val cached = runCatching { gson.fromJson<List<Release?>>(prefs.getString(KEY_RELEASES, "[]"), object : TypeToken<List<Release?>>() {}.type) }.getOrNull().orEmpty()
            .filter { it != null && it.title != null && it.artist != null && it.date != null }.map { it!! }
        if (!fresh && System.currentTimeMillis() - prefs.getLong(KEY_RELEASES_AT, 0) < 6 * 3600_000L) return cached
        val since = java.time.LocalDate.now().minusDays(60).toString()
        val artists = loadFollows().filterKeys { it.startsWith("dzartist:") }
        if (artists.isEmpty()) return emptyList()
        val found = artists.map { (ref, f) -> pool.submit<List<Release>> {
            val id = ref.substringAfter(':')
            runCatching {
                get("https://api.deezer.com/artist/$id/albums?limit=15")?.let { JsonParser.parseString(it).asJsonObject }
                    ?.getAsJsonArray("data")?.map { it.asJsonObject }?.mapNotNull { a ->
                        val date = str(a, "release_date") ?: return@mapNotNull null
                        if (date < since) null else Release(a["id"].asLong, str(a, "title").orEmpty(), f.name, str(a, "cover_xl") ?: str(a, "cover_big"), date)
                    }
            }.getOrNull().orEmpty()
        } }.flatMap { runCatching { it.get(30, TimeUnit.SECONDS) }.getOrNull().orEmpty() }
            .distinctBy { it.albumId }.sortedByDescending { it.date }
        // notify releases missing at the last check (not on the first one, to avoid flooding)
        val known = cached.map { it.albumId }.toSet()
        if (prefs.getLong(KEY_RELEASES_AT, 0) > 0) found.filter { it.albumId !in known }.take(5).forEach { notifyRelease(it) }
        prefs.edit().putString(KEY_RELEASES, gson.toJson(found)).putLong(KEY_RELEASES_AT, System.currentTimeMillis()).apply()
        return found
    }

    private fun notifyRelease(r: Release) {
        val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val channel = "lite_releases"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).createNotificationChannel(
                android.app.NotificationChannel(channel, ctx.getString(R.string.drift_group_releases), android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pi = android.app.PendingIntent.getActivity(ctx, r.albumId.toInt(), open, android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(com.eddyizm.tempus.R.drawable.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(R.string.lite_new_release, r.artist)).setContentText(r.title).setContentIntent(pi).setAutoCancel(true).build()
        runCatching { nm.notify(("rel" + r.albumId).hashCode(), n) }
    }

    // ------------------------------------------------------------------ search

    data class ArtistHit(val id: Long, val name: String, val picture: String?, val fans: Int)
    data class AlbumHit(val id: Long, val title: String, val artist: String, val cover: String?, val type: String = "album", val year: String? = null)

    /** Discography (Deezer), newest first. */
    fun artistAlbums(id: Long): List<AlbumHit> = runCatching {
        get("https://api.deezer.com/artist/$id/albums?limit=100")?.let { JsonParser.parseString(it).asJsonObject }?.getAsJsonArray("data")
            ?.map { it.asJsonObject }?.map {
                AlbumHit(it["id"].asLong, str(it, "title").orEmpty(), "", str(it, "cover_big"), str(it, "record_type") ?: "album",
                    str(it, "release_date")?.take(4))
            }?.sortedByDescending { it.year ?: "" }?.distinctBy { it.title.lowercase() }
    }.getOrNull().orEmpty()

    /** Opens the artist page from the name (player, track menu). Blocking. */
    @JvmStatic
    fun artistRefByName(name: String?): Pair<String, ArtistHit>? {
        val first = name?.split(Regex(",| feat\\.? | ft\\.? | & | x ", RegexOption.IGNORE_CASE))?.firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return null
        val hits = searchArtists(first, 5)
        val h = hits.firstOrNull { LiteMatch.norm(it.name) == LiteMatch.norm(first) } ?: hits.firstOrNull() ?: return null
        return "dzartist:${h.id}" to h
    }

    fun searchArtists(q: String, limit: Int = 8): List<ArtistHit> = runCatching {
        get("https://api.deezer.com/search/artist?limit=$limit&q=" + java.net.URLEncoder.encode(q, "UTF-8"))
            ?.let { JsonParser.parseString(it).asJsonObject }?.getAsJsonArray("data")?.map { it.asJsonObject }?.map {
                ArtistHit(it["id"].asLong, str(it, "name").orEmpty(), str(it, "picture_big") ?: str(it, "picture_medium"), it["nb_fan"]?.asInt ?: 0)
            }
    }.getOrNull().orEmpty()

    /** Songs from Deezer (reliable order and data); audio is looked up on YouTube only when playback starts. */
    fun searchTracks(q: String, limit: Int = 25): List<RawTrack> = runCatching {
        get("https://api.deezer.com/search/track?limit=$limit&q=" + java.net.URLEncoder.encode(q, "UTF-8"))
            ?.let { JsonParser.parseString(it).asJsonObject }?.getAsJsonArray("data")?.map { it.asJsonObject }?.mapNotNull { t ->
                RawTrack(str(t, "title") ?: return@mapNotNull null, str(t.getAsJsonObject("artist"), "name").orEmpty(), t["duration"]?.asInt ?: 0,
                    t.getAsJsonObject("album")?.let { str(it, "cover_big") ?: str(it, "cover_medium") })
            }
    }.getOrNull().orEmpty()

    fun searchAlbums(q: String, limit: Int = 10): List<AlbumHit> = runCatching {
        get("https://api.deezer.com/search/album?limit=$limit&q=" + java.net.URLEncoder.encode(q, "UTF-8"))
            ?.let { JsonParser.parseString(it).asJsonObject }?.getAsJsonArray("data")?.map { it.asJsonObject }?.map {
                AlbumHit(it["id"].asLong, str(it, "title").orEmpty(), str(it.getAsJsonObject("artist"), "name").orEmpty(), str(it, "cover_big"))
            }
    }.getOrNull().orEmpty()

    private fun artistList(url: String): List<ArtistHit> = runCatching {
        get(url)?.let { JsonParser.parseString(it).asJsonObject }?.getAsJsonArray("data")
            ?.map { it.asJsonObject }?.map { ArtistHit(it["id"].asLong, str(it, "name").orEmpty(), str(it, "picture_big"), it["nb_fan"]?.asInt ?: 0) }
    }.getOrNull().orEmpty()

    /**
     * Artists for the first launch: all those of the chosen genres (Deezer gives ~50 per genre),
     * interleaved genre by genre, plus the top 100 chart; no duplicates.
     */
    fun genreArtists(genres: List<Int>): List<ArtistHit> {
        // genre "artists" and "chart" endpoints ignore the genre and return the national chart: the genre radios don't
        val perGenre = genres.take(8).map { g -> pool.submit<List<ArtistHit>> { radioArtists(g) } }
            .map { runCatching { it.get(25, TimeUnit.SECONDS) }.getOrNull().orEmpty() }
        val chart = artistList("https://api.deezer.com/chart/0/artists?limit=100")
        val mixed = mutableListOf<ArtistHit>()
        val max = perGenre.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) perGenre.forEach { l -> l.getOrNull(i)?.let { mixed += it } }
        return (mixed + chart).distinctBy { it.id }
    }

    /**
     * Discover: similar to the artists you follow (most recurring first), then the most played of your
     * genres; without those you already follow. Cached 12 h.
     */
    fun discover(): List<ArtistHit> {
        val key = "lite_discover"
        val followed = loadFollows().keys.filter { it.startsWith("dzartist:") }.mapNotNull { it.substringAfter(':').toLongOrNull() }.toSet()
        runCatching {
            val (at, json) = prefs.getString(key, "")!!.split('|', limit = 2)
            if (System.currentTimeMillis() - at.toLong() < 12 * 3600_000L)
                @Suppress("SENSELESS_COMPARISON")
                return gson.fromJson<List<ArtistHit?>>(json, object : TypeToken<List<ArtistHit?>>() {}.type)
                    .filter { it != null && it.name != null && it.id !in followed }.map { it!! }
        }
        val related = followed.take(15).map { id -> pool.submit<List<ArtistHit>> { relatedArtists(id) } }
            .flatMap { runCatching { it.get(20, TimeUnit.SECONDS) }.getOrNull().orEmpty() }
        val ranked = related.groupBy { it.id }.values.sortedByDescending { it.size }.map { it.first() }
        val out = (ranked + genreArtists(LiteApp.genres.ifEmpty { listOf(0) })).distinctBy { it.id }.filter { it.id !in followed }
        if (out.isNotEmpty()) prefs.edit().putString(key, System.currentTimeMillis().toString() + "|" + gson.toJson(out)).apply()
        return out
    }

    /** Most frequent artists across the first radios of a genre. */
    private fun radioArtists(genre: Int): List<ArtistHit> = runCatching {
        val radios = get("https://api.deezer.com/genre/$genre/radios")?.let { JsonParser.parseString(it).asJsonObject }
            ?.getAsJsonArray("data")?.map { it.asJsonObject["id"].asLong }?.take(3).orEmpty()
        radios.flatMap { r ->
            get("https://api.deezer.com/radio/$r/tracks?limit=50")?.let { JsonParser.parseString(it).asJsonObject }
                ?.getAsJsonArray("data")?.mapNotNull { it.asJsonObject.getAsJsonObject("artist") }.orEmpty()
        }.groupBy { it["id"].asLong }.values.sortedByDescending { it.size }.take(40).map { l ->
            val a = l.first(); val id = a["id"].asLong
            ArtistHit(id, str(a, "name").orEmpty(), str(a, "picture_big") ?: "https://api.deezer.com/artist/$id/image?size=big", 0)
        }
    }.getOrNull().orEmpty()

    /** Similar artists (Deezer). */
    fun relatedArtists(id: Long): List<ArtistHit> = artistList("https://api.deezer.com/artist/$id/related?limit=20")

    fun isFollowed(ref: String) = ref in loadFollows()

    /** Follows an artist without opening its page (first launch, search). */
    fun followArtist(a: ArtistHit): Boolean {
        val m = loadFollows()
        m["dzartist:${a.id}"] = Follow(a.name, a.picture, 0, false, System.currentTimeMillis() / 1000)
        saveFollows(m)
        prefs.edit().putLong(KEY_RELEASES_AT, 0).remove("lite_discover").apply()
        return true
    }

    private const val KEY_ADDED = "lite_added_playlists"
    private fun added(): List<String> = prefs.getString(KEY_ADDED, "")!!.split('\n').filter { it.isNotBlank() }

    private val SP_LINK = Regex("""open\.spotify\.com/(?:[a-z-]+/)?(?:embed/)?playlist/([A-Za-z0-9]{22})""")
    private val DZ_LINK = Regex("""deezer\.com/(?:[a-z]{2}/)?playlist/(\d+)""")

    /** Playlist added by pasting a Spotify or Deezer link (link.deezer.com too); id or null. */
    fun add(url: String): String? {
        var u = url.trim()
        if ("link.deezer.com" in u || "deezer.page.link" in u) {
            u = runCatching { http.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { it.request.url.toString() } }.getOrDefault(u)
        }
        val ref = SP_LINK.find(u)?.let { "spotify:" + it.groupValues[1] } ?: DZ_LINK.find(u)?.let { "deezer:" + it.groupValues[1] } ?: return null
        raw(ref, fresh = true) ?: return null
        val list = added()
        if (ref !in list) prefs.edit().putString(KEY_ADDED, (listOf(ref) + list).joinToString("\n")).apply()
        return ref
    }

    // ------------------------------------------------------------------ library (Navidrome)

    private data class Hit(val songId: String?, val at: Long)
    private val matchFile get() = File(dir, "matches.json")
    private val matches: ConcurrentHashMap<String, Hit> by lazy {
        ConcurrentHashMap<String, Hit>().also { m ->
            runCatching { gson.fromJson<Map<String, Hit>>(matchFile.readText(), object : TypeToken<Map<String, Hit>>() {}.type).let { m.putAll(it) } }
        }
    }

    @Synchronized private fun saveMatches() { runCatching { matchFile.writeText(gson.toJson(matches)) } }

    // ------------------------------------------------------------------ backup

    /** Followed, added, library matches and copies of followed playlists (for offline). */
    fun backup(): JsonObject = JsonObject().apply {
        addProperty(KEY_FOLLOWS, prefs.getString(KEY_FOLLOWS, "{}"))
        addProperty(KEY_ADDED, prefs.getString(KEY_ADDED, ""))
        addProperty("matches", gson.toJson(matches))
        addProperty("tracks", LiteTracks.exportJson())
        addProperty("local_playlists", LitePlaylists.exportJson())
        val pls = JsonObject()
        loadFollows().keys.forEach { ref -> raw(ref)?.let { pls.addProperty(ref, gson.toJson(it)) } }
        add("playlists", pls)
    }

    fun restore(o: JsonObject): Boolean = runCatching {
        o[KEY_FOLLOWS]?.asString?.let { prefs.edit().putString(KEY_FOLLOWS, it).apply() }
        o[KEY_ADDED]?.asString?.let { prefs.edit().putString(KEY_ADDED, it).apply() }
        o["matches"]?.asString?.let { m ->
            gson.fromJson<Map<String, Hit>>(m, object : TypeToken<Map<String, Hit>>() {}.type)?.let { matches.putAll(it); saveMatches() }
        }
        o["tracks"]?.asString?.let { LiteTracks.importJson(it) }
        o["local_playlists"]?.asString?.let { LitePlaylists.importJson(it) }
        o.getAsJsonObject("playlists")?.entrySet()?.forEach { (ref, v) ->
            val r = gson.fromJson(v.asString, Raw::class.java)
            memo[ref] = r
            diskFile(ref).writeText(v.asString)
        }
        true
    }.getOrElse { Log.w(TAG, "restore", it); false }

    /** Navidrome id of the song, or null if missing (result cached: missing ones are rechecked after 12 h). */
    private fun librarySong(t: RawTrack): String? {
        val key = LiteTracks.idOf(t.title, t.artist)
        matches[key]?.let { h -> if (h.songId != null || System.currentTimeMillis() - h.at < 12 * 3600_000L) return h.songId }
        if (com.eddyizm.tempus.util.NetworkUtil.isOffline()) return null
        val title = LiteMatch.norm(t.title)
        val arts = LiteMatch.artists(t.artist)
        val client = App.getSubsonicClientInstance(false).searchingClient
        var found: String? = null
        for (q in listOfNotNull("$title ${arts.firstOrNull().orEmpty()}".trim(), title)) {
            val songs = runCatching { client.search3(q, 15, 0, 0, 0, 0, 0).execute().body()?.subsonicResponse?.searchResult3?.songs }.getOrNull().orEmpty()
            found = songs.filter { s ->
                val st = LiteMatch.norm(s.title ?: "")
                val sa = LiteMatch.norm((s.artist ?: "") + " " + (s.albumArtist ?: ""))
                (st == title || (title.length > 4 && (st.startsWith(title) || title.startsWith(st)) && st.isNotEmpty())) &&
                    (arts.isEmpty() || arts.any { sa.contains(it) })
            }.minByOrNull { s -> if (t.duration > 0 && (s.duration ?: 0) > 0) abs(s.duration!! - t.duration) else 0 }?.id
            if (found != null) break
        }
        matches[key] = Hit(found, System.currentTimeMillis())
        return found
    }

    /** Playlist with matched songs: Navidrome id if you have them, otherwise a YouTube song "yt:..." */
    fun playlist(ref: String, fresh: Boolean = false): DriftSpotify.Playlist? {
        val r = raw(ref, fresh) ?: return null
        // Lite is online only: no library to match against, every song is a YouTube song
        val tracks = r.tracks.map { t ->
            val id = LiteTracks.register(t.title, t.artist, t.duration, t.cover ?: r.cover)
            DriftSpotify.Track(t.title, t.artist, t.duration, id)
        }
        val f = loadFollows()[ref]
        return DriftSpotify.Playlist(ref, r.name, r.cover, tracks, f != null, f?.autoDownload == true)
    }

    // ------------------------------------------------------------------ followed (on the phone)

    data class Follow(val name: String, val cover: String?, val count: Int, val autoDownload: Boolean,
                      val lastSync: Long = 0, val lastNew: Int = 0, val keys: List<String> = emptyList())
    private const val KEY_FOLLOWS = "lite_follows"

    @Synchronized
    @Suppress("SENSELESS_COMPARISON")
    private fun loadFollows(): MutableMap<String, Follow> = runCatching {
        gson.fromJson<MutableMap<String, Follow?>>(prefs.getString(KEY_FOLLOWS, "{}"), object : TypeToken<MutableMap<String, Follow?>>() {}.type)
            ?.filter { (k, f) -> k != null && f != null && f.name != null && f.keys != null }?.mapValues { it.value!! }?.toMutableMap()
    }.getOrNull() ?: mutableMapOf()

    @Synchronized
    private fun saveFollows(m: Map<String, Follow>) = prefs.edit().putString(KEY_FOLLOWS, gson.toJson(m)).apply()

    fun follow(ref: String, autoDownload: Boolean): Boolean {
        val r = raw(ref) ?: return false
        val m = loadFollows()
        m[ref] = Follow(r.name, r.cover, r.tracks.size, autoDownload, System.currentTimeMillis() / 1000, 0,
            r.tracks.map { LiteTracks.idOf(it.title, it.artist) })
        saveFollows(m)
        if (ref.startsWith("dzartist:")) prefs.edit().putLong(KEY_RELEASES_AT, 0).apply()
        return true
    }

    fun unfollow(ref: String): Boolean { val m = loadFollows(); m.remove(ref); saveFollows(m); return true }

    fun follows(): List<String> = loadFollows().keys.toList()

    fun followed(): List<DriftSpotify.Followed> = loadFollows().filterKeys { !it.startsWith("dzartist:") }.map { (ref, f) ->
        DriftSpotify.Followed(DriftSpotify.Entry(ref, DriftSpotify.G_FOLLOWED, f.name, f.cover, f.count, ref.substringBefore(':'), true),
            0, f.lastSync, f.lastNew)
    }

    /** Refreshes followed playlists (at most once every 12 h, from MainActivity) and counts new songs. */
    fun refreshFollows(): Int {
        runCatching { loadReleases(fresh = true) }
        val m = loadFollows()
        var changed = 0
        for ((ref, f) in m.toMap()) {
            val r = raw(ref, fresh = true) ?: continue
            val keys = r.tracks.map { LiteTracks.idOf(it.title, it.artist) }
            val new = keys.count { it !in f.keys }
            m[ref] = f.copy(name = r.name, cover = r.cover, count = r.tracks.size, keys = keys,
                lastSync = if (new > 0) System.currentTimeMillis() / 1000 else f.lastSync, lastNew = if (new > 0) new else f.lastNew)
            if (new > 0) changed++
        }
        saveFollows(m)
        return changed
    }

    // ------------------------------------------------------------------ offline (YouTube songs on the phone)

    // songs (playlist index -> id) of the last "Save offline" of each playlist; the state lives in LiteDownloads
    private val bulks = ConcurrentHashMap<String, List<Pair<Int, String>>>()

    /** Saves to the phone the playlist songs not there yet (single LiteDownloads queue). */
    fun download(ref: String): DriftSpotify.Bulk? {
        val p = playlist(ref) ?: return null
        // a downloaded album or playlist is kept with your playlists (not artist pages: those are followed)
        if (!ref.startsWith("dzartist:") && !p.followed) follow(ref, false)
        val todo = p.tracks.withIndex().filter { (_, t) -> LiteTracks.isLite(t.songId) && !LiteTracks.isDownloaded(t.songId!!) }
            .map { (i, t) -> i to t.songId!! }
        bulks[ref] = todo
        LiteDownloads.enqueue(todo.map { it.second })
        return state(ref)
    }

    fun state(ref: String): DriftSpotify.Bulk? {
        val todo = bulks[ref] ?: return null
        val items = todo.associate { (i, id) ->
            val it = LiteDownloads.state(id)
            i to when (it?.state) {
                LiteDownloads.S.QUEUED -> DriftSpotify.ItemState("queued", 0f)
                LiteDownloads.S.SEARCHING -> DriftSpotify.ItemState("searching", 0f)
                LiteDownloads.S.DOWNLOADING -> DriftSpotify.ItemState("downloading", it.progress)
                LiteDownloads.S.NOT_FOUND -> DriftSpotify.ItemState("not_found", 0f)
                LiteDownloads.S.FAILED, LiteDownloads.S.CANCELLED -> DriftSpotify.ItemState("failed", 0f)
                else -> DriftSpotify.ItemState("done", 100f)
            }
        }
        val total = items.size
        val done = items.values.count { it.state == "done" }
        val failed = items.values.count { it.state == "failed" || it.state == "not_found" }
        val active = items.values.any { it.state == "queued" || it.state == "searching" || it.state == "downloading" }
        val progress = if (total == 0) 100f else items.values.sumOf { (if (it.state == "done" || it.state == "failed" || it.state == "not_found") 100f else it.progress).toDouble() }.toFloat() / total
        return DriftSpotify.Bulk(active, total, done + failed, items.values.count { it.state == "queued" },
            items.values.count { it.state == "not_found" }, active, progress, done, failed, items)
    }

    /** Followed playlists kept offline: library songs via Drift downloads, YouTube songs here. */
    fun downloadFollowed(context: Context, only: String?): Int {
        if (Preferences.isDownloadWifiOnly() && isMetered(context)) return 0
        val refs = only?.let { listOf(it) } ?: loadFollows().filterValues { it.autoDownload }.keys.toList()
        var n = 0
        for (ref in refs) {
            val b = download(ref) ?: continue
            n += b.total
        }
        return n
    }

    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.isActiveNetworkMetered
    }
}
