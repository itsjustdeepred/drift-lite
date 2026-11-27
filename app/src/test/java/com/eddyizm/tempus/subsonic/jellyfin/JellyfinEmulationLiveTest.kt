package com.eddyizm.tempus.subsonic.jellyfin

import com.eddyizm.tempus.subsonic.api.albumsonglist.AlbumSongListService
import com.eddyizm.tempus.subsonic.api.bookmarks.BookmarksService
import com.eddyizm.tempus.subsonic.api.browsing.BrowsingService
import com.eddyizm.tempus.subsonic.api.internetradio.InternetRadioService
import com.eddyizm.tempus.subsonic.api.mediaannotation.MediaAnnotationService
import com.eddyizm.tempus.subsonic.api.open.OpenService
import com.eddyizm.tempus.subsonic.api.playlist.PlaylistService
import com.eddyizm.tempus.subsonic.api.podcast.PodcastService
import com.eddyizm.tempus.subsonic.api.searching.SearchingService
import com.eddyizm.tempus.subsonic.api.system.SystemService
import com.eddyizm.tempus.subsonic.base.ApiResponse
import com.eddyizm.tempus.subsonic.models.Bookmarks
import com.eddyizm.tempus.subsonic.models.BookmarksDeserializer
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.models.ResponseStatus
import com.eddyizm.tempus.subsonic.models.SubsonicResponse
import com.eddyizm.tempus.subsonic.utils.EmptyDateTypeAdapter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Drift: tests the Subsonic emulation against a real Jellyfin, with the app's Retrofit interfaces and
 * models. Skips itself if the server does not respond (CI).
 *
 * Server: DRIFT_JF_URL (default http://localhost:18096), user DRIFT_JF_USER / DRIFT_JF_PASS
 * (drift / driftpass), DRIFT_JF_KIND jellyfin (default) or emby (e.g. http://localhost:18097/emby). Expected library: "Alpha Band - First Light" (3 songs, 2001, Rock) and
 * "Beta Trio - Blue Hours" (2 songs, 2015, Jazz; "River Swing" with synced .lrc).
 */
class JellyfinEmulationLiveTest {

    companion object {
        private val BASE = (System.getenv("DRIFT_JF_URL") ?: "http://localhost:18096").trimEnd('/')
        private val USER = System.getenv("DRIFT_JF_USER") ?: "drift"
        private val PASS = System.getenv("DRIFT_JF_PASS") ?: "driftpass"
        private val KIND = System.getenv("DRIFT_JF_KIND") ?: ServerType.JELLYFIN
        private val EMBY = KIND == ServerType.EMBY

        private var credential: JellyfinCredential? = null
        private lateinit var client: OkHttpClient
        private lateinit var retrofit: Retrofit
        private lateinit var gson: Gson

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val plain = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            credential = try {
                JellyfinClientInfo.deviceId = "drift-unit-test"
                JellyfinAuthenticator.authenticate(BASE, KIND, USER, PASS, plain)
            } catch (e: Exception) {
                null
            }
            client = plain.newBuilder().addInterceptor(JellyfinInterceptor()).build()
            // Same Gson configuration as RetrofitClient
            gson = GsonBuilder()
                .registerTypeAdapter(Date::class.java, EmptyDateTypeAdapter())
                .registerTypeAdapter(Bookmarks::class.java, BookmarksDeserializer())
                .setLenient()
                .create()
            retrofit = Retrofit.Builder()
                .baseUrl("$BASE/rest/")
                .addConverterFactory(GsonConverterFactory.create(gson))
                .client(client)
                .build()
        }
    }

    private fun live() = assumeTrue("Test Jellyfin not reachable at $BASE", credential != null)

    private fun params(cred: JellyfinCredential = credential!!) = mapOf(
        "u" to USER, "p" to cred.encode(), "v" to "1.15.0", "c" to "Drift", "f" to "json",
    )

    private fun ok(call: Call<ApiResponse>): SubsonicResponse {
        val response = call.execute()
        assertTrue("HTTP ${response.code()}", response.isSuccessful)
        val body = response.body()!!.subsonicResponse
        assertEquals("response not ok: ${body.error?.message}", ResponseStatus.OK, body.status)
        assertEquals("1.16.1", body.version)
        assertEquals(true, body.openSubsonic)
        return body
    }

    private fun raw(method: String, vararg q: Pair<String, String>): com.google.gson.JsonObject {
        val url = StringBuilder("$BASE/rest/$method?u=$USER&p=${credential!!.encode()}&v=1.15.0&c=Drift&f=json")
        q.forEach { (k, v) -> url.append('&').append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8")) }
        client.newCall(Request.Builder().url(url.toString()).build()).execute().use {
            return JsonParser.parseString(it.body.string()).asJsonObject.getAsJsonObject("subsonic-response")
        }
    }

    private val system get() = retrofit.create(SystemService::class.java)
    private val browsing get() = retrofit.create(BrowsingService::class.java)
    private val lists get() = retrofit.create(AlbumSongListService::class.java)
    private val search get() = retrofit.create(SearchingService::class.java)
    private val playlists get() = retrofit.create(PlaylistService::class.java)
    private val annotation get() = retrofit.create(MediaAnnotationService::class.java)

    private fun allSongs(): List<Child> =
        ok(search.search3(params(), "", 100, 0, 0, 0, 0, 0, null)).searchResult3!!.songs!!

    private fun song(title: String): Child = allSongs().first { it.title == title }

    // ---------------------------------------------------------------------------------------

    @Test
    fun pingAndSystem() {
        live()
        ok(system.ping(params()))
        assertNotNull(ok(system.getLicense(params())).license)
        val ext = ok(system.getOpenSubsonicExtensions(params())).openSubsonicExtensions!!
        // Drift: Emby does not expose lyrics via the API
        assertEquals(!EMBY, ext.any { it.name == "songLyrics" })
    }

    @Test
    fun pingWithRevokedTokenIsSubsonicAuthError() {
        live()
        val bad = credential!!.copy(token = "0123456789abcdef0123456789abcdef")
        val body = system.ping(params(bad)).execute().body()!!.subsonicResponse
        assertEquals(ResponseStatus.FAILED, body.status)
        assertEquals(40, body.error!!.code)
    }

    @Test
    fun browsing() {
        live()
        val folders = ok(browsing.getMusicFolders(params())).musicFolders!!.musicFolders!!
        assertEquals(1, folders.size)

        val index = ok(browsing.getArtists(params(), null)).artists!!.indices!!
        val artists = index.flatMap { it.artists!! }
        assertTrue(artists.map { it.name }.containsAll(listOf("Alpha Band", "Beta Trio")))
        assertEquals("A", index.first { i -> i.artists!!.any { it.name == "Alpha Band" } }.name)

        val alpha = artists.first { it.name == "Alpha Band" }
        val artist = ok(browsing.getArtist(params(), alpha.id)).artist!!
        assertEquals("Alpha Band", artist.name)
        assertEquals(listOf("First Light"), artist.albums!!.map { it.name })

        val album = ok(browsing.getAlbum(params(), artist.albums!!.first().id)).album!!
        assertEquals("First Light", album.name)
        assertEquals("Alpha Band", album.artist)
        assertEquals(alpha.id, album.artistId)
        assertEquals(2001, album.year)
        assertEquals(album.id, album.coverArtId)
        assertEquals(3, album.songCount)
        val songs = album.songs!!
        assertEquals(listOf("Dawn Song", "Noon Song", "Dusk Song"), songs.map { it.title })
        assertEquals(listOf(1, 2, 3), songs.map { it.track })
        songs.forEach {
            assertEquals("mp3", it.suffix)
            assertEquals("audio/mpeg", it.contentType)
            assertTrue("duration ${it.duration}", it.duration in 11..13)
            assertEquals(album.id, it.albumId)
            assertEquals(album.id, it.coverArtId)
            assertEquals(alpha.id, it.artistId)
            assertEquals("Alpha Band", it.albumArtist)
            assertTrue((it.bitrate ?: 0) in 100..200)
            assertNotNull(it.created)
        }

        val one = ok(browsing.getSong(params(), songs[1].id)).song!!
        assertEquals("Noon Song", one.title)
        assertEquals("Rock", one.genre)

        val indexes = ok(browsing.getIndexes(params(), null, null)).indexes!!
        assertTrue(indexes.indices!!.flatMap { it.artists!! }.any { it.name == "Beta Trio" })
        val dir = ok(browsing.getMusicDirectory(params(), alpha.id)).directory!!
        assertTrue(dir.children!!.all { it.isDir })
        val albumDir = ok(browsing.getMusicDirectory(params(), album.id)).directory!!
        assertEquals(3, albumDir.children!!.size)

        val genres = ok(browsing.getGenres(params())).genres!!.genres!!
        assertEquals(3, genres.first { it.genre == "Rock" }.songCount)
        assertEquals(1, genres.first { it.genre == "Jazz" }.albumCount)
    }

    @Test
    fun albumLists() {
        live()
        fun names(type: String, from: Int? = null, to: Int? = null) =
            ok(lists.getAlbumList2(params(), type, 50, 0, from, to, null)).albumList2!!.albums!!.map { it.name }

        assertEquals(listOf("Blue Hours", "First Light"), names("alphabeticalByName"))
        assertEquals(2, names("newest").size)
        assertEquals(2, names("random").size)
        assertEquals(listOf("Blue Hours"), names("byYear", 2010, 2020))
        assertEquals(listOf("First Light"), names("byYear", 1990, 2005))
        assertEquals(listOf("Blue Hours", "First Light"), names("byYear", 2030, 1900))

        assertEquals(listOf("First Light"), raw("getAlbumList2", "type" to "byGenre", "genre" to "Rock")
            .getAsJsonObject("albumList2").getAsJsonArray("album").map { it.asJsonObject["name"].asString })

        // paging
        assertEquals(listOf("First Light"),
            ok(lists.getAlbumList2(params(), "alphabeticalByName", 1, 1, null, null, null)).albumList2!!.albums!!.map { it.name })

        // recently played: the album shows up after a scrobble
        val dusk = song("Dusk Song")
        ok(annotation.scrobble(params(), dusk.id, true))
        assertTrue(names("recent").contains("First Light"))
        assertTrue(names("frequent").contains("First Light"))

        ok(lists.getRandomSongs(params(), 3, null, null, null))
        val random = raw("getRandomSongs", "size" to "3").getAsJsonObject("randomSongs").getAsJsonArray("song")
        assertEquals(3, random.size())
        val jazz = raw("getSongsByGenre", "genre" to "Jazz", "count" to "10")
            .getAsJsonObject("songsByGenre").getAsJsonArray("song")
        assertEquals(2, jazz.size())
    }

    @Test
    fun searchAndStarred() {
        live()
        val r = ok(search.search3(params(), "Dawn", 10, 0, 10, 0, 10, 0, null)).searchResult3!!
        assertEquals(listOf("Dawn Song"), r.songs!!.map { it.title })
        assertTrue(r.albums.isNullOrEmpty())

        val byAlbum = ok(search.search3(params(), "Blue", 10, 0, 10, 0, 10, 0, null)).searchResult3!!
        assertEquals(listOf("Blue Hours"), byAlbum.albums!!.map { it.name })

        val byArtist = ok(search.search3(params(), "Beta", 10, 0, 10, 0, 10, 0, null)).searchResult3!!
        assertEquals(listOf("Beta Trio"), byArtist.artists!!.map { it.name })

        assertEquals(5, allSongs().size)

        // star / unstar and getStarred2
        val noon = song("Noon Song")
        val album = ok(lists.getAlbumList2(params(), "alphabeticalByName", 50, 0, null, null, null)).albumList2!!.albums!!
            .first { it.name == "Blue Hours" }
        ok(annotation.star(params(), noon.id, album.id, album.artistId))
        var starred = ok(lists.getStarred2(params())).starred2!!
        assertTrue(starred.songs!!.any { it.id == noon.id && it.starred != null })
        assertTrue(starred.albums!!.any { it.id == album.id })
        assertTrue(starred.artists!!.any { it.id == album.artistId })
        assertNotNull(ok(browsing.getSong(params(), noon.id)).song!!.starred)
        assertEquals(listOf("Blue Hours"),
            ok(lists.getAlbumList2(params(), "starred", 50, 0, null, null, null)).albumList2!!.albums!!.map { it.name })

        ok(annotation.unstar(params(), noon.id, album.id, album.artistId))
        starred = ok(lists.getStarred2(params())).starred2!!
        assertFalse(starred.songs!!.any { it.id == noon.id })
        assertFalse(starred.albums!!.any { it.id == album.id })
        assertNull(ok(browsing.getSong(params(), noon.id)).song!!.starred)

        // rating -> like (Emby accepts but does not store it: no rating)
        ok(annotation.setRating(params(), noon.id, 5))
        assertEquals(if (EMBY) null else 5, ok(browsing.getSong(params(), noon.id)).song!!.userRating)
        ok(annotation.setRating(params(), noon.id, 0))
        assertNull(ok(browsing.getSong(params(), noon.id)).song!!.userRating)
    }

    @Test
    fun playlistsRoundTrip() {
        live()
        val songs = allSongs()
        val a = songs.first { it.title == "Dawn Song" }
        val b = songs.first { it.title == "Ocean Waltz" }
        val c = songs.first { it.title == "River Swing" }

        ok(playlists.createPlaylist(params(), null, "Drift test", arrayListOf(a.id, b.id)))
        val created = ok(playlists.getPlaylists(params())).playlists!!.playlists!!.first { it.name == "Drift test" }
        assertEquals(2, created.songCount)
        assertEquals(USER, created.owner)

        var pl = ok(playlists.getPlaylist(params(), created.id)).playlist!!
        assertEquals(created.id, pl.id)
        assertEquals(listOf("Dawn Song", "Ocean Waltz"), pl.entries!!.map { it.title })

        // remove the first, add a song, rename
        ok(playlists.updatePlaylist(params(), created.id, "Drift test 2", null, arrayListOf(c.id), arrayListOf(0)))
        pl = ok(playlists.getPlaylist(params(), created.id)).playlist!!
        assertEquals("Drift test 2", pl.name)
        assertEquals(listOf("Ocean Waltz", "River Swing"), pl.entries!!.map { it.title })

        // createPlaylist with playlistId replaces the songs
        ok(playlists.createPlaylist(params(), created.id, null, arrayListOf(a.id)))
        pl = ok(playlists.getPlaylist(params(), created.id)).playlist!!
        assertEquals(listOf("Dawn Song"), pl.entries!!.map { it.title })

        ok(playlists.deletePlaylist(params(), created.id))
        assertFalse(ok(playlists.getPlaylists(params())).playlists!!.playlists.orEmpty().any { it.id == created.id })
    }

    @Test
    fun scrobbleCountsPlay() {
        live()
        val noon = song("Noon Song")
        val before = ok(browsing.getSong(params(), noon.id)).song!!.playCount ?: 0
        ok(annotation.scrobble(params(), noon.id, false))
        ok(annotation.scrobble(params(), noon.id, true))
        assertEquals(before + 1, ok(browsing.getSong(params(), noon.id)).song!!.playCount)
    }

    @Test
    fun lyricsMixesAndInfo() {
        live()
        val river = song("River Swing")
        val lyrics = ok(retrofit.create(OpenService::class.java).getLyricsBySongId(params(), river.id)).lyricsList!!
        if (EMBY) {
            assertTrue(lyrics.structuredLyrics!!.isEmpty())
        } else {
        val structured = lyrics.structuredLyrics!!.single()
        assertTrue(structured.synced)
        assertEquals(listOf(0, 4500, 9000), structured.line!!.map { it.start })
        assertEquals("River line two", structured.line!![1].value)
        }

        // song without lyrics: empty list, not an error
        val none = ok(retrofit.create(OpenService::class.java).getLyricsBySongId(params(), song("Dawn Song").id)).lyricsList!!
        assertTrue(none.structuredLyrics!!.isEmpty())

        val mix = raw("getSimilarSongs2", "id" to river.id, "count" to "10")
        assertEquals("ok", mix["status"].asString)
        assertTrue(mix.getAsJsonObject("similarSongs2").getAsJsonArray("song").size() > 0)

        val top = raw("getTopSongs", "artist" to "Alpha Band", "count" to "10")
            .getAsJsonObject("topSongs").getAsJsonArray("song")
        assertEquals(3, top.size())
        assertEquals(0, raw("getTopSongs", "artist" to "Nessuno", "count" to "10")
            .getAsJsonObject("topSongs").getAsJsonArray("song").size())

        ok(browsing.getArtistInfo2(params(), river.artistId))
        ok(browsing.getAlbumInfo2(params(), river.albumId))
    }

    @Test
    fun unsupportedAndEmptyEndpoints() {
        live()
        val unsupported = browsing.getVideos(params()).execute().body()!!.subsonicResponse
        assertEquals(ResponseStatus.FAILED, unsupported.status)
        assertEquals(if (EMBY) "Not supported on Emby" else "Not supported on Jellyfin", unsupported.error!!.message)

        assertTrue(ok(retrofit.create(PodcastService::class.java).getPodcasts(params(), false, null)).podcasts!!.channels.isNullOrEmpty())
        assertTrue(ok(retrofit.create(InternetRadioService::class.java).getInternetRadioStations(params()))
            .internetRadioStations!!.internetRadioStations.isNullOrEmpty())
        assertTrue(ok(retrofit.create(BookmarksService::class.java).getBookmarks(params())).bookmarks!!.bookmarks.isNullOrEmpty())
        assertNull(ok(retrofit.create(BookmarksService::class.java).getPlayQueue(params())).playQueue)
        ok(retrofit.create(BookmarksService::class.java).savePlayQueue(params(), listOf("x"), "x", 0))

        // missing parameter: Subsonic error 10, not an exception
        val missing = browsing.getAlbum(params(), null).execute().body()!!.subsonicResponse
        assertEquals(10, missing.error!!.code)
    }

    @Test
    fun streamSupportsRangeThroughInterceptor() {
        live()
        val id = song("Dawn Song").id
        val url = "$BASE/rest/stream?u=$USER&p=${credential!!.encode()}&v=1.15.0&c=Drift&maxBitRate=0&format=raw&estimateContentLength=true&id=$id"
        client.newCall(Request.Builder().url(url).header("Range", "bytes=100-199").build()).execute().use {
            assertEquals(206, it.code)
            assertEquals("bytes 100-199/193272".substringBefore('/'), it.header("Content-Range")!!.substringBefore('/'))
            assertEquals(100, it.body.bytes().size)
            assertTrue(it.header("Content-Type")!!.startsWith("audio/"))
        }
        // from the start: it really is an mp3
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-2").build()).execute().use {
            val head = it.body.bytes()
            assertTrue(String(head, Charsets.ISO_8859_1) == "ID3" || (head[0].toInt() and 0xFF) == 0xFF)
        }
    }

    @Test
    fun streamRewriteForExoPlayerWorksWithoutInterceptor() {
        live()
        val id = song("Ocean Waltz").id
        // as MusicUtil.getStreamUri builds it, with the old token replaced by the current one
        val stale = credential!!.copy(token = "deadbeefdeadbeefdeadbeefdeadbeef")
        val url = "$BASE/rest/stream?u=$USER&p=${stale.encode()}&v=1.15.0&c=Drift&estimateContentLength=true&id=$id"
        val rewrite = JellyfinMedia.rewrite(url, credential)!!
        assertTrue(rewrite.url.encodedPath.endsWith("/Audio/$id/stream"))
        val plain = OkHttpClient()
        val req = Request.Builder().url(rewrite.url).header("Range", "bytes=0-9")
        rewrite.headers.forEach { (k, v) -> req.header(k, v) }
        plain.newCall(req.build()).execute().use {
            assertEquals(206, it.code)
            assertEquals(10, it.body.bytes().size)
        }
    }

    @Test
    fun transcodedStreamDownloadAndCover() {
        live()
        val songItem = song("River Swing")
        val p = credential!!.encode()
        client.newCall(Request.Builder()
            .url("$BASE/rest/stream?u=$USER&p=$p&v=1.15.0&c=Drift&maxBitRate=64&format=opus&id=${songItem.id}").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("audio/ogg", it.header("Content-Type"))
            assertTrue(it.body.bytes().size > 1000)
        }
        client.newCall(Request.Builder().url("$BASE/rest/download?u=$USER&p=$p&v=1.15.0&c=Drift&id=${songItem.id}").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals(songItem.size, it.body.bytes().size.toLong())
        }
        client.newCall(Request.Builder().url("$BASE/rest/getCoverArt?u=$USER&p=$p&v=1.15.0&c=Drift&size=120&id=${songItem.coverArtId}").build()).execute().use {
            // Drift: the test Emby does not import cover.jpg: the album has no image (404, placeholder in the app)
            if (EMBY && it.code == 404) return
            assertEquals(200, it.code)
            assertTrue(it.header("Content-Type")!!.startsWith("image/"))
        }
    }
}
