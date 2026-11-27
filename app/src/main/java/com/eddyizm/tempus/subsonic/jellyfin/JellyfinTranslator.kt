package com.eddyizm.tempus.subsonic.jellyfin

import com.eddyizm.tempus.subsonic.jellyfin.Json.put
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale

/**
 * Drift: answers the app's Subsonic calls by querying Jellyfin/Emby and returning JSON shaped
 * like `subsonic-response`, which the app's models then read unchanged. Ids are the Jellyfin
 * ones (GUIDs), used as is as Subsonic ids.
 *
 * [fetch] runs an HTTP request: in the app it is the interceptor's `chain.proceed` (so client
 * certificate and custom headers apply), in tests any OkHttpClient.
 *
 * A Jellyfin HTTP error becomes a Subsonic error (status "failed"); a network error
 * stays an IOException, so the app treats it as an unreachable server.
 */
class JellyfinTranslator(
    private val base: HttpUrl,
    private val credential: JellyfinCredential,
    private val username: String?,
    private val fetch: (Request) -> Response,
) {
    class HttpError(val status: Int, message: String) : RuntimeException(message)
    private class Unsupported : RuntimeException()

    private val emby = credential.isEmby
    private val uid = credential.userId
    private val serverName = if (emby) "Emby" else "Jellyfin"

    // ---------------------------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------------------------

    /** The full `{"subsonic-response": {...}}` response for [method] with the parameters of [query]. */
    @Throws(IOException::class)
    fun handle(method: String, query: HttpUrl): JsonObject {
        val q = Params(query)
        val body = try {
            JsonObject().also { dispatch(method, q, it) }
        } catch (e: HttpError) {
            return failed(errorCode(e.status), "$serverName: ${e.message}")
        } catch (e: Unsupported) {
            return failed(0, "Not supported on $serverName")
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            // Drift: an unexpected Jellyfin response must not crash the app
            return failed(0, "$serverName: ${e.javaClass.simpleName}: ${e.message}")
        }
        return wrap(body.apply {
            addProperty("status", "ok")
        })
    }

    private fun errorCode(status: Int) = when (status) {
        400 -> 10
        401 -> 40
        403 -> 50
        404 -> 70
        else -> 0
    }

    private fun wrap(body: JsonObject): JsonObject {
        body.addProperty("version", SUBSONIC_VERSION)
        body.addProperty("type", if (emby) "emby" else "jellyfin")
        body.addProperty("serverVersion", "drift-emulation")
        body.addProperty("openSubsonic", true)
        return JsonObject().apply { add("subsonic-response", body) }
    }

    private fun failed(code: Int, message: String): JsonObject {
        val body = JsonObject()
        body.addProperty("status", "failed")
        body.add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
        return wrap(body)
    }

    private fun dispatch(method: String, q: Params, out: JsonObject) {
        when (method) {
            "ping" -> ping()
            "getLicense" -> out.add("license", JsonObject().apply { addProperty("valid", true) })
            "getOpenSubsonicExtensions" -> out.add("openSubsonicExtensions", extensions())
            "getMusicFolders" -> out.add("musicFolders", JsonObject().apply { add("musicFolder", musicFolders()) })
            "getArtists" -> out.add("artists", artistsIndex(q.str("musicFolderId"), id3 = true))
            "getIndexes" -> out.add("indexes", artistsIndex(q.str("musicFolderId"), id3 = false))
            "getMusicDirectory" -> out.add("directory", musicDirectory(q.required("id")))
            "getArtist" -> out.add("artist", artist(q.required("id")))
            "getAlbum" -> out.add("album", album(q.required("id")))
            "getSong" -> out.add("song", song(item(q.required("id"), SONG_FIELDS)))
            "getAlbumList2" -> out.add("albumList2", JsonObject().apply { add("album", albumList(q)) })
            "getAlbumList" -> out.add("albumList", JsonObject().apply {
                add("album", Json.array(albumList(q).map { albumAsChild(it.asJsonObject) }))
            })
            "getRandomSongs" -> out.add("randomSongs", songsObject(randomSongs(q)))
            "getSongsByGenre" -> out.add("songsByGenre", songsObject(songsByGenre(q)))
            "getStarred2" -> out.add("starred2", starred(id3 = true))
            "getStarred" -> out.add("starred", starred(id3 = false))
            "getGenres" -> out.add("genres", JsonObject().apply { add("genre", genres()) })
            "search3" -> out.add("searchResult3", search(q, id3 = true))
            "search2" -> out.add("searchResult2", search(q, id3 = false))
            "getPlaylists" -> out.add("playlists", JsonObject().apply { add("playlist", playlists()) })
            "getPlaylist" -> out.add("playlist", playlistWithEntries(q.required("id")))
            "createPlaylist" -> out.add("playlist", createPlaylist(q))
            "updatePlaylist" -> updatePlaylist(q)
            "deletePlaylist" -> send("DELETE", url(listOf("Items", q.required("id"))))
            "star" -> star(q, true)
            "unstar" -> star(q, false)
            "setRating" -> setRating(q.required("id"), q.int("rating") ?: 0)
            "scrobble" -> scrobble(q)
            "getLyricsBySongId" -> out.add("lyricsList", lyricsList(q.required("id")))
            "getLyrics" -> out.add("lyrics", JsonObject())
            "getSimilarSongs2" -> out.add("similarSongs2", songsObject(instantMix(q.required("id"), q.int("count") ?: 50)))
            "getSimilarSongs" -> out.add("similarSongs", songsObject(instantMix(q.required("id"), q.int("count") ?: 50)))
            "getTopSongs" -> out.add("topSongs", songsObject(topSongs(q.str("artist"), q.int("count") ?: 50)))
            "getArtistInfo2" -> out.add("artistInfo2", artistInfo(q.required("id"), q.int("count") ?: 20))
            "getArtistInfo" -> out.add("artistInfo", artistInfo(q.required("id"), q.int("count") ?: 20))
            "getAlbumInfo2", "getAlbumInfo" -> out.add("albumInfo", albumInfo(q.required("id")))
            // Drift: the play queue stays local only
            "getPlayQueue", "savePlayQueue" -> Unit
            "getNowPlaying" -> out.add("nowPlaying", JsonObject())
            "getScanStatus" -> out.add("scanStatus", scanStatus(false))
            "startScan" -> {
                runCatching { send("POST", url(listOf("Library", "Refresh"))) }
                out.add("scanStatus", scanStatus(true))
            }
            // Features Jellyfin lacks: empty lists instead of errors on screen
            "getPodcasts" -> out.add("podcasts", JsonObject())
            "getNewestPodcasts" -> out.add("newestPodcasts", JsonObject())
            "getInternetRadioStations" -> out.add("internetRadioStations", JsonObject())
            "getShares" -> out.add("shares", JsonObject())
            "getBookmarks" -> out.add("bookmarks", JsonObject())
            else -> throw Unsupported()
        }
    }

    // ---------------------------------------------------------------------------------------
    // System
    // ---------------------------------------------------------------------------------------

    private fun ping() {
        // Drift: checks the token; 401 becomes Subsonic error 40 (wrong credentials)
        if (emby) get(url(listOf("Users", uid))) else get(url(listOf("Users", "Me")))
    }

    private fun extensions(): JsonArray {
        val list = JsonArray()
        if (!emby) list.add(JsonObject().apply {
            addProperty("name", "songLyrics")
            add("versions", JsonArray().apply { add(1) })
        })
        return list
    }

    private fun scanStatus(scanning: Boolean) = JsonObject().apply {
        addProperty("scanning", scanning)
        addProperty("count", 0)
    }

    private fun musicFolders(): JsonArray {
        val views = if (emby) get(url(listOf("Users", uid, "Views"))) else get(url(listOf("UserViews"), "userId" to uid))
        return Json.array(Json.objects(views?.asJsonObject, "Items")
            .filter { Json.str(it, "CollectionType") == "music" }
            .map { v -> JsonObject().apply { put("id", Json.str(v, "Id")); put("name", Json.str(v, "Name")) } })
    }

    // ---------------------------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------------------------

    private fun albumArtists(parentId: String?): List<JsonObject> {
        val res = get(url(listOf("Artists", "AlbumArtists"),
            "userId" to uid, "ParentId" to parentId, "SortBy" to "SortName", "SortOrder" to "Ascending",
            "Fields" to "SortName,ChildCount", "EnableImageTypes" to "Primary"))
        return Json.objects(res?.asJsonObject, "Items")
    }

    private fun artistsIndex(musicFolderId: String?, id3: Boolean): JsonObject {
        val groups = linkedMapOf<String, JsonArray>()
        for (a in albumArtists(musicFolderId)) {
            val name = Json.str(a, "SortName")?.takeIf { it.isNotBlank() } ?: Json.str(a, "Name") ?: ""
            val first = name.trim().firstOrNull()?.uppercaseChar()
            val key = if (first != null && first.isLetter()) first.toString() else "#"
            groups.getOrPut(key) { JsonArray() }.add(if (id3) artistId3(a) else artistLegacy(a))
        }
        val index = JsonArray()
        groups.toSortedMap().forEach { (k, v) ->
            index.add(JsonObject().apply { addProperty("name", k); add("artist", v) })
        }
        return JsonObject().apply {
            addProperty("ignoredArticles", "")
            if (!id3) addProperty("lastModified", 0)
            add("index", index)
        }
    }

    private fun musicDirectory(id: String): JsonObject {
        val it = item(id, "ChildCount,SortName")
        val type = Json.str(it, "Type")
        val children: List<JsonObject> = when (type) {
            "MusicArtist" -> albumsOfArtist(id)
            "MusicAlbum" -> albumSongs(id)
            else -> items("ParentId" to id, "SortBy" to "IsFolder,SortName", "Fields" to SONG_FIELDS)
        }
        return JsonObject().apply {
            put("id", Json.str(it, "Id"))
            put("name", Json.str(it, "Name"))
            put("parent", Json.str(it, "ParentId"))
            put("starred", starredDate(it))
            put("playCount", Json.long(Json.obj(it, "UserData"), "PlayCount"))
            add("child", Json.array(children.map { c ->
                if (Json.str(c, "Type") == "Audio") song(c) else folderAsChild(c, id)
            }))
        }
    }

    private fun artist(id: String): JsonObject {
        val a = item(id, "ChildCount,SortName")
        val albums = albumsOfArtist(id)
        val own = albums.mapNotNull { Json.str(it, "Id") }.toSet()
        // Drift: "appears on" = albums the artist contributes to without being the album artist
        val appears = runCatching {
            items("IncludeItemTypes" to "MusicAlbum", "Recursive" to "true",
                (if (emby) "ArtistIds" else "ContributingArtistIds") to id,
                "SortBy" to "ProductionYear,SortName", "Fields" to ALBUM_FIELDS)
        }.getOrDefault(emptyList()).filter { Json.str(it, "Id") !in own }

        return artistId3(a).apply {
            addProperty("albumCount", albums.size)
            add("album", Json.array(albums.map { album(it) }))
            if (appears.isNotEmpty()) add("appearsOn", Json.array(appears.map { album(it) }))
        }
    }

    private fun albumsOfArtist(artistId: String): List<JsonObject> =
        items("IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "AlbumArtistIds" to artistId,
            "SortBy" to "ProductionYear,SortName", "SortOrder" to "Ascending", "Fields" to ALBUM_FIELDS)

    private fun albumSongs(albumId: String): List<JsonObject> =
        items("ParentId" to albumId, "IncludeItemTypes" to "Audio", "Recursive" to "true",
            "SortBy" to "ParentIndexNumber,IndexNumber,SortName", "SortOrder" to "Ascending", "Fields" to SONG_FIELDS)

    private fun album(id: String): JsonObject {
        val a = item(id, ALBUM_FIELDS)
        val songs = albumSongs(id)
        return album(a).apply {
            addProperty("songCount", songs.size)
            addProperty("duration", songs.sumOf { seconds(it) ?: 0 })
            add("song", Json.array(songs.map { song(it) }))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lists
    // ---------------------------------------------------------------------------------------

    private fun albumList(q: Params): JsonArray {
        val type = q.str("type") ?: "newest"
        val size = (q.int("size") ?: 10).coerceIn(1, 500)
        val offset = (q.int("offset") ?: 0).coerceAtLeast(0)
        val folder = q.str("musicFolderId")

        if (type == "recent" || type == "frequent") return Json.array(playedAlbums(type == "frequent", size, offset, folder).map { album(it) })

        val params = mutableListOf<Pair<String, String?>>(
            "IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "ParentId" to folder,
            "StartIndex" to offset.toString(), "Limit" to size.toString(), "Fields" to ALBUM_FIELDS,
        )
        when (type) {
            "newest" -> params += listOf("SortBy" to "DateCreated,SortName", "SortOrder" to "Descending")
            "random" -> params += listOf("SortBy" to "Random")
            "alphabeticalByArtist" -> params += listOf("SortBy" to "AlbumArtist,SortName", "SortOrder" to "Ascending")
            "starred" -> params += listOf("Filters" to "IsFavorite", "SortBy" to "SortName", "SortOrder" to "Ascending")
            "highest" -> params += listOf("SortBy" to "CommunityRating,SortName", "SortOrder" to "Descending")
            "byGenre" -> params += listOf("Genres" to q.str("genre"), "SortBy" to "SortName", "SortOrder" to "Ascending")
            "byYear" -> {
                val from = q.int("fromYear") ?: 0
                val to = q.int("toYear") ?: 9999
                val lo = minOf(from, to).coerceAtLeast(1000)
                val hi = maxOf(from, to).coerceAtMost(2100)
                params += listOf(
                    "Years" to (lo..hi).joinToString(","),
                    "SortBy" to "ProductionYear,SortName",
                    "SortOrder" to if (from <= to) "Ascending" else "Descending",
                )
            }
            else -> params += listOf("SortBy" to "SortName", "SortOrder" to "Ascending")
        }
        return Json.array(items(*params.toTypedArray()).map { album(it) })
    }

    /**
     * Drift: Jellyfin keeps no "last played" or "play count" per album: they are derived from the
     * played tracks, grouped by album in the right order.
     */
    private fun playedAlbums(frequent: Boolean, size: Int, offset: Int, folder: String?): List<JsonObject> {
        val songs = items("IncludeItemTypes" to "Audio", "Recursive" to "true", "ParentId" to folder,
            "Filters" to "IsPlayed", "SortBy" to if (frequent) "PlayCount,DatePlayed" else "DatePlayed",
            "SortOrder" to "Descending", "Limit" to ((offset + size) * 6).coerceAtMost(1000).toString())
        val order: List<String> = if (frequent) {
            val sums = linkedMapOf<String, Long>()
            songs.forEach { s ->
                val a = Json.str(s, "AlbumId") ?: return@forEach
                sums[a] = (sums[a] ?: 0) + (Json.long(Json.obj(s, "UserData"), "PlayCount") ?: 0)
            }
            sums.entries.sortedByDescending { it.value }.map { it.key }
        } else {
            songs.mapNotNull { Json.str(it, "AlbumId") }.distinct()
        }
        val page = order.drop(offset).take(size)
        if (page.isEmpty()) return emptyList()
        // Drift: Emby filters by Ids only when type and recursion are given too
        val byId = items("Ids" to page.joinToString(","), "IncludeItemTypes" to "MusicAlbum", "Recursive" to "true",
            "Fields" to ALBUM_FIELDS).associateBy { Json.str(it, "Id") }
        return page.mapNotNull { byId[it] }
    }

    private fun randomSongs(q: Params): List<JsonObject> {
        val from = q.int("fromYear")
        val to = q.int("toYear")
        val years = if (from != null || to != null) {
            val lo = minOf(from ?: 1000, to ?: 2100).coerceAtLeast(1000)
            val hi = maxOf(from ?: 1000, to ?: 2100).coerceAtMost(2100)
            (lo..hi).joinToString(",")
        } else null
        return items("IncludeItemTypes" to "Audio", "Recursive" to "true", "SortBy" to "Random",
            "ParentId" to q.str("musicFolderId"), "Genres" to q.str("genre"), "Years" to years,
            "Limit" to (q.int("size") ?: 10).coerceIn(1, 500).toString(), "Fields" to SONG_FIELDS)
    }

    private fun songsByGenre(q: Params): List<JsonObject> =
        items("IncludeItemTypes" to "Audio", "Recursive" to "true", "Genres" to q.required("genre"),
            "ParentId" to q.str("musicFolderId"), "SortBy" to "AlbumArtist,Album,ParentIndexNumber,IndexNumber",
            "SortOrder" to "Ascending", "StartIndex" to (q.int("offset") ?: 0).toString(),
            "Limit" to (q.int("count") ?: 10).coerceIn(1, 500).toString(), "Fields" to SONG_FIELDS)

    private fun starred(id3: Boolean): JsonObject {
        val artists = Json.objects(get(url(listOf("Artists"), "userId" to uid, "IsFavorite" to "true",
            "Filters" to "IsFavorite", "SortBy" to "SortName", "Fields" to "SortName"))?.asJsonObject, "Items")
            .filter { Json.bool(Json.obj(it, "UserData"), "IsFavorite") == true }
        val albums = items("IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "Filters" to "IsFavorite",
            "SortBy" to "SortName", "Fields" to ALBUM_FIELDS)
        val songs = items("IncludeItemTypes" to "Audio", "Recursive" to "true", "Filters" to "IsFavorite",
            "SortBy" to "AlbumArtist,Album,ParentIndexNumber,IndexNumber", "Fields" to SONG_FIELDS)
        return JsonObject().apply {
            add("artist", Json.array(artists.map { if (id3) artistId3(it) else artistLegacy(it) }))
            add("album", Json.array(albums.map { if (id3) album(it) else albumAsChild(album(it)) }))
            add("song", Json.array(songs.map { song(it) }))
        }
    }

    private fun genres(): JsonArray {
        val res = get(url(listOf("MusicGenres"), "userId" to uid, "Fields" to "ItemCounts", "SortBy" to "SortName"))
        val list = Json.objects(res?.asJsonObject, "Items")
        // Drift: Emby gives no counts: they are counted separately, but only when there are few genres
        val countMissing = list.size <= 150
        fun count(genreId: String?, type: String): Int {
            if (!countMissing || genreId == null) return 0
            val u = if (emby) url(listOf("Users", uid, "Items"), "IncludeItemTypes" to type, "Recursive" to "true", "GenreIds" to genreId, "Limit" to "0")
            else url(listOf("Items"), "userId" to uid, "IncludeItemTypes" to type, "Recursive" to "true", "GenreIds" to genreId, "Limit" to "0")
            return runCatching { Json.int(get(u)?.asJsonObject, "TotalRecordCount") ?: 0 }.getOrDefault(0)
        }
        return Json.array(list.map { g ->
            JsonObject().apply {
                put("value", Json.str(g, "Name"))
                addProperty("songCount", Json.int(g, "SongCount") ?: count(Json.str(g, "Id"), "Audio"))
                addProperty("albumCount", Json.int(g, "AlbumCount") ?: count(Json.str(g, "Id"), "MusicAlbum"))
            }
        })
    }

    private fun search(q: Params, id3: Boolean): JsonObject {
        // Drift: empty query (or "") = everything, as Navidrome does
        val term = q.str("query")?.trim()?.removeSurrounding("\"")?.trim()?.takeIf { it.isNotEmpty() }
        val folder = q.str("musicFolderId")
        fun count(name: String, def: Int) = (q.int(name) ?: def).coerceIn(0, 500)
        fun offset(name: String) = (q.int(name) ?: 0).coerceAtLeast(0).toString()

        val artistCount = count("artistCount", 20)
        val albumCount = count("albumCount", 20)
        val songCount = count("songCount", 20)

        val artists = if (artistCount == 0) emptyList() else Json.objects(get(url(listOf("Artists"),
            "userId" to uid, "searchTerm" to term, "ParentId" to folder, "SortBy" to "SortName",
            "StartIndex" to offset("artistOffset"), "Limit" to artistCount.toString(), "Fields" to "SortName"))?.asJsonObject, "Items")
        val albums = if (albumCount == 0) emptyList() else items("IncludeItemTypes" to "MusicAlbum", "Recursive" to "true",
            "searchTerm" to term, "ParentId" to folder, "SortBy" to "SortName",
            "StartIndex" to offset("albumOffset"), "Limit" to albumCount.toString(), "Fields" to ALBUM_FIELDS)
        val songs = if (songCount == 0) emptyList() else items("IncludeItemTypes" to "Audio", "Recursive" to "true",
            "searchTerm" to term, "ParentId" to folder, "SortBy" to "SortName",
            "StartIndex" to offset("songOffset"), "Limit" to songCount.toString(), "Fields" to SONG_FIELDS)

        return JsonObject().apply {
            add("artist", Json.array(artists.map { if (id3) artistId3(it) else artistLegacy(it) }))
            add("album", Json.array(albums.map { if (id3) album(it) else albumAsChild(album(it)) }))
            add("song", Json.array(songs.map { song(it) }))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Playlist
    // ---------------------------------------------------------------------------------------

    private fun playlists(): JsonArray =
        Json.array(items("IncludeItemTypes" to "Playlist", "Recursive" to "true", "SortBy" to "SortName",
            "Fields" to "ChildCount,DateCreated,Overview")
            .filter { (Json.str(it, "MediaType") ?: "Audio") == "Audio" }
            .map { playlist(it) })

    private fun playlistEntries(id: String): List<JsonObject> {
        val res = get(url(listOf("Playlists", id, "Items"), "userId" to uid, "Fields" to SONG_FIELDS))
        return Json.objects(res?.asJsonObject, "Items")
    }

    private fun playlistWithEntries(id: String): JsonObject {
        val p = item(id, "ChildCount,DateCreated,Overview")
        val entries = playlistEntries(id).filter { Json.str(it, "Type") == "Audio" }
        return playlist(p).apply {
            addProperty("songCount", entries.size)
            addProperty("duration", entries.sumOf { seconds(it) ?: 0 })
            add("entry", Json.array(entries.map { song(it) }))
        }
    }

    private fun createPlaylist(q: Params): JsonObject {
        val existing = q.str("playlistId")
        val name = q.str("name")
        val songs = q.all("songId")
        if (existing != null) {
            // Subsonic: createPlaylist with playlistId replaces the tracks
            val entryIds = playlistEntries(existing).mapNotNull { Json.str(it, "PlaylistItemId") ?: Json.str(it, "Id") }
            removeEntries(existing, entryIds)
            addToPlaylist(existing, songs)
            if (name != null) renamePlaylist(existing, name)
            return playlistWithEntries(existing)
        }
        val created = if (emby) {
            send("POST", url(listOf("Playlists"), "Name" to (name ?: "Playlist"), "Ids" to songs.joinToString(",").ifEmpty { null },
                "UserId" to uid, "MediaType" to "Audio"))
        } else {
            send("POST", url(listOf("Playlists")), JsonObject().apply {
                addProperty("Name", name ?: "Playlist")
                add("Ids", Json.array(songs.map { com.google.gson.JsonPrimitive(it) }))
                addProperty("UserId", uid)
                addProperty("MediaType", "Audio")
            })
        }
        val id = Json.str(created?.asJsonObject, "Id") ?: throw HttpError(500, "playlist not created")
        return playlistWithEntries(id)
    }

    private fun updatePlaylist(q: Params) {
        val id = q.required("playlistId")
        q.str("name")?.let { renamePlaylist(id, it) }
        val toRemove = q.all("songIndexToRemove").mapNotNull { it.toIntOrNull() }.toSet()
        if (toRemove.isNotEmpty()) {
            val entries = playlistEntries(id)
            val ids = entries.withIndex().filter { it.index in toRemove }
                .mapNotNull { Json.str(it.value, "PlaylistItemId") ?: Json.str(it.value, "Id") }
            removeEntries(id, ids)
        }
        addToPlaylist(id, q.all("songIdToAdd"))
    }

    private fun addToPlaylist(id: String, songs: List<String>) {
        songs.chunked(100).forEach { chunk ->
            send("POST", url(listOf("Playlists", id, "Items"), "ids" to chunk.joinToString(","), "userId" to uid))
        }
    }

    private fun removeEntries(id: String, entryIds: List<String>) {
        entryIds.chunked(100).forEach { chunk ->
            send("DELETE", url(listOf("Playlists", id, "Items"), "entryIds" to chunk.joinToString(",")))
        }
    }

    private fun renamePlaylist(id: String, name: String) {
        if (emby) {
            // Drift: Emby renames only by rewriting the whole item
            val it = get(url(listOf("Users", uid, "Items", id)))?.asJsonObject ?: return
            it.addProperty("Name", name)
            send("POST", url(listOf("Items", id)), it)
        } else {
            send("POST", url(listOf("Playlists", id)), JsonObject().apply { addProperty("Name", name) })
        }
    }

    // ---------------------------------------------------------------------------------------
    // Annotations
    // ---------------------------------------------------------------------------------------

    private fun favoriteUrl(id: String) =
        if (emby) url(listOf("Users", uid, "FavoriteItems", id)) else url(listOf("UserFavoriteItems", id), "userId" to uid)

    private fun star(q: Params, add: Boolean) {
        val ids = q.all("id") + q.all("albumId") + q.all("artistId")
        ids.filter { it.isNotBlank() }.distinct().forEach { send(if (add) "POST" else "DELETE", favoriteUrl(it)) }
    }

    /**
     * Drift: Jellyfin only has like / dislike: 3-5 stars = like, 1-2 = dislike, 0 = none.
     * Emby accepts the call but saves nothing: there the rating is not recorded.
     */
    private fun setRating(id: String, rating: Int) {
        if (emby) return
        val path = if (emby) listOf("Users", uid, "Items", id, "Rating") else listOf("UserItems", id, "Rating")
        val user = if (emby) null else uid
        if (rating <= 0) send("DELETE", url(path, "userId" to user))
        else send("POST", url(path, "userId" to user, "likes" to (rating >= 3).toString()))
    }

    private fun scrobble(q: Params) {
        val submission = q.str("submission")?.toBooleanStrictOrNull() ?: true
        for (id in q.all("id").filter { it.isNotBlank() }) {
            if (!submission) {
                // Drift: "now playing" is informational only: if the server rejects it, never mind
                runCatching {
                    val mediaSource = Json.objects(item(id, "MediaSources"), "MediaSources").firstOrNull()
                    send("POST", url(listOf("Sessions", "Playing")), JsonObject().apply {
                        addProperty("ItemId", id)
                        put("MediaSourceId", Json.str(mediaSource, "Id") ?: id)
                        addProperty("PlaySessionId", java.util.UUID.randomUUID().toString().replace("-", ""))
                        addProperty("PositionTicks", 0)
                        addProperty("CanSeek", true)
                        addProperty("PlayMethod", "DirectStream")
                    })
                }.onFailure { if (it is IOException) throw it }
            } else {
                // Drift: "stopped at end of track" counts the play on Jellyfin (PlayCount, last played);
                // Emby already counts it at start (submission=false), here it only closes the session
                val ticks = Json.long(item(id, ""), "RunTimeTicks") ?: 0L
                send("POST", url(listOf("Sessions", "Playing", "Stopped")), JsonObject().apply {
                    addProperty("ItemId", id)
                    addProperty("PositionTicks", ticks)
                })
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lyrics, mixes, info
    // ---------------------------------------------------------------------------------------

    private fun lyricsList(id: String): JsonObject {
        val out = JsonObject()
        val list = JsonArray()
        out.add("structuredLyrics", list)
        if (emby) return out
        val res = try {
            get(url(listOf("Audio", id, "Lyrics")))?.asJsonObject
        } catch (e: HttpError) {
            if (e.status == 404) return out else throw e
        } ?: return out

        val lines = Json.objects(res, "Lyrics")
        if (lines.isEmpty()) return out
        val meta = Json.obj(res, "Metadata")
        val synced = lines.any { Json.long(it, "Start") != null }
        list.add(JsonObject().apply {
            put("displayArtist", Json.str(meta, "Artist"))
            put("displayTitle", Json.str(meta, "Title"))
            addProperty("lang", "xxx")
            addProperty("offset", ((Json.long(meta, "Offset") ?: 0L) / 10_000L).toInt())
            addProperty("synced", synced)
            add("line", Json.array(lines.map { l ->
                JsonObject().apply {
                    if (synced) put("start", ((Json.long(l, "Start") ?: 0L) / 10_000L).toInt())
                    addProperty("value", Json.str(l, "Text") ?: "")
                }
            }))
        })
        return out
    }

    private fun instantMix(id: String, count: Int): List<JsonObject> =
        Json.objects(get(url(listOf("Items", id, "InstantMix"), "userId" to uid,
            "limit" to count.coerceIn(1, 500).toString(), "Fields" to SONG_FIELDS))?.asJsonObject, "Items")
            .filter { Json.str(it, "Type") == "Audio" }

    private fun topSongs(artistName: String?, count: Int): List<JsonObject> {
        if (artistName.isNullOrBlank()) return emptyList()
        val artist = try {
            get(url(listOf("Artists", artistName), "userId" to uid))?.asJsonObject
        } catch (e: HttpError) {
            // Drift: unknown artist = 404 on Jellyfin, 500 on Emby
            if (e.status == 401) throw e else return emptyList()
        }
        val artistId = Json.str(artist, "Id") ?: return emptyList()
        return items("IncludeItemTypes" to "Audio", "Recursive" to "true", "ArtistIds" to artistId,
            "SortBy" to "PlayCount,CommunityRating,SortName", "SortOrder" to "Descending",
            "Limit" to count.coerceIn(1, 500).toString(), "Fields" to SONG_FIELDS)
    }

    private fun artistInfo(id: String, count: Int): JsonObject {
        val a = item(id, "Overview,ProviderIds")
        val similar = runCatching {
            Json.objects(get(url(listOf("Artists", id, "Similar"), "userId" to uid,
                "limit" to count.coerceIn(1, 100).toString()))?.asJsonObject, "Items")
        }.getOrDefault(emptyList())
        return JsonObject().apply {
            put("biography", Json.str(a, "Overview"))
            put("musicBrainzId", Json.str(Json.obj(a, "ProviderIds"), "MusicBrainzArtist"))
            add("similarArtist", Json.array(similar.map { artistId3(it) }))
        }
    }

    private fun albumInfo(id: String): JsonObject {
        val a = item(id, "Overview,ProviderIds")
        return JsonObject().apply {
            put("notes", Json.str(a, "Overview"))
            put("musicBrainzId", Json.str(Json.obj(a, "ProviderIds"), "MusicBrainzAlbum"))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Jellyfin items -> Subsonic models
    // ---------------------------------------------------------------------------------------

    private fun songsObject(songs: List<JsonObject>) = JsonObject().apply { add("song", Json.array(songs.map { song(it) })) }

    private fun seconds(o: JsonObject): Int? = Json.long(o, "RunTimeTicks")?.let { (it / 10_000_000L).toInt() }

    private fun hasPrimary(o: JsonObject) = Json.obj(o, "ImageTags")?.has("Primary") == true

    private fun starredDate(o: JsonObject): String? {
        val ud = Json.obj(o, "UserData") ?: return null
        if (Json.bool(ud, "IsFavorite") != true) return null
        // Drift: Jellyfin does not know when an item was starred: use the item's date
        return date(Json.str(o, "DateCreated")) ?: EPOCH
    }

    private fun userRating(o: JsonObject): Int? = when (Json.bool(Json.obj(o, "UserData"), "Likes")) {
        true -> 5
        false -> 1
        null -> null
    }

    private fun idName(o: JsonObject) = JsonObject().apply {
        put("id", Json.str(o, "Id"))
        put("name", Json.str(o, "Name"))
    }

    private fun artistId3(a: JsonObject) = JsonObject().apply {
        put("id", Json.str(a, "Id"))
        put("name", Json.str(a, "Name"))
        put("coverArt", Json.str(a, "Id"))
        addProperty("albumCount", Json.int(a, "AlbumCount") ?: Json.int(a, "ChildCount") ?: 0)
        put("starred", starredDate(a))
        put("sortName", Json.str(a, "SortName"))
    }

    private fun artistLegacy(a: JsonObject) = JsonObject().apply {
        put("id", Json.str(a, "Id"))
        put("name", Json.str(a, "Name"))
        put("starred", starredDate(a))
        put("userRating", userRating(a))
    }

    private fun album(a: JsonObject): JsonObject {
        val id = Json.str(a, "Id")
        val albumArtists = Json.objects(a, "AlbumArtists")
        val artistName = Json.str(a, "AlbumArtist") ?: albumArtists.mapNotNull { Json.str(it, "Name") }.joinToString(", ").ifEmpty { null }
        val genres = Json.strings(a, "Genres")
        val ud = Json.obj(a, "UserData")
        return JsonObject().apply {
            put("id", id)
            put("name", Json.str(a, "Name"))
            put("artist", artistName)
            put("displayArtist", artistName)
            put("artistId", albumArtists.firstNotNullOfOrNull { Json.str(it, "Id") })
            put("coverArt", id)
            put("songCount", Json.int(a, "ChildCount") ?: Json.int(a, "SongCount"))
            put("duration", seconds(a))
            put("playCount", Json.long(ud, "PlayCount"))
            put("played", date(Json.str(ud, "LastPlayedDate")))
            put("created", date(Json.str(a, "DateCreated")))
            put("starred", starredDate(a))
            addProperty("year", Json.int(a, "ProductionYear") ?: 0)
            put("genre", genres.firstOrNull())
            add("genres", Json.array(genres.map { g -> JsonObject().apply { addProperty("name", g) } }))
            add("artists", Json.array(albumArtists.map { idName(it) }))
            put("userRating", userRating(a))
            put("sortName", Json.str(a, "SortName"))
            put("musicBrainzId", Json.str(Json.obj(a, "ProviderIds"), "MusicBrainzAlbum"))
        }
    }

    private fun playlist(p: JsonObject) = JsonObject().apply {
        val id = Json.str(p, "Id")
        val created = date(Json.str(p, "DateCreated"))
        put("id", id)
        put("name", Json.str(p, "Name"))
        put("comment", Json.str(p, "Overview"))
        put("owner", username)
        addProperty("public", false)
        addProperty("songCount", Json.int(p, "ChildCount") ?: 0)
        addProperty("duration", seconds(p) ?: 0)
        put("created", created)
        put("changed", created)
        put("coverArt", id)
    }

    /** Album (already converted) in the Child shape of getAlbumList / getStarred / search2. */
    private fun albumAsChild(a: JsonObject) = JsonObject().apply {
        put("id", Json.str(a, "id"))
        put("parent", Json.str(a, "artistId"))
        addProperty("isDir", true)
        put("title", Json.str(a, "name"))
        put("album", Json.str(a, "name"))
        put("artist", Json.str(a, "artist"))
        put("year", Json.int(a, "year"))
        put("genre", Json.str(a, "genre"))
        put("coverArt", Json.str(a, "coverArt"))
        put("created", Json.str(a, "created"))
        put("starred", Json.str(a, "starred"))
        put("albumId", Json.str(a, "id"))
        put("artistId", Json.str(a, "artistId"))
    }

    private fun folderAsChild(o: JsonObject, parent: String) = JsonObject().apply {
        val id = Json.str(o, "Id")
        put("id", id)
        put("parent", parent)
        addProperty("isDir", true)
        put("title", Json.str(o, "Name"))
        put("album", Json.str(o, "Album") ?: Json.str(o, "Name"))
        put("artist", Json.str(o, "AlbumArtist"))
        put("year", Json.int(o, "ProductionYear"))
        put("coverArt", id)
        put("created", date(Json.str(o, "DateCreated")))
        put("starred", starredDate(o))
    }

    private fun song(s: JsonObject): JsonObject {
        val id = Json.str(s, "Id")
        val source = Json.objects(s, "MediaSources").firstOrNull()
        val stream = Json.objects(source ?: s, "MediaStreams").firstOrNull { Json.str(it, "Type") == "Audio" }
        val container = (Json.str(source, "Container") ?: Json.str(s, "Container"))?.substringBefore(',')?.lowercase(Locale.ROOT)
        val artists = Json.objects(s, "ArtistItems")
        val albumArtists = Json.objects(s, "AlbumArtists")
        val artistName = Json.strings(s, "Artists").joinToString(", ").ifEmpty { null } ?: Json.str(s, "AlbumArtist")
        val albumArtist = Json.str(s, "AlbumArtist") ?: albumArtists.mapNotNull { Json.str(it, "Name") }.joinToString(", ").ifEmpty { null }
        val albumId = Json.str(s, "AlbumId")
        val coverArt = when {
            hasPrimary(s) -> id
            albumId != null -> albumId
            else -> id
        }
        val bitrate = (Json.long(source, "Bitrate") ?: Json.long(stream, "BitRate"))?.let { (it / 1000).toInt() }
        val genres = Json.strings(s, "Genres")
        val ud = Json.obj(s, "UserData")
        val title = Json.str(s, "Name")
        val track = Json.int(s, "IndexNumber")

        return JsonObject().apply {
            put("id", id)
            put("parent", albumId ?: Json.str(s, "ParentId"))
            addProperty("isDir", false)
            put("title", title)
            put("album", Json.str(s, "Album"))
            put("artist", artistName)
            put("track", track)
            put("year", Json.int(s, "ProductionYear"))
            put("genre", genres.firstOrNull())
            add("genres", Json.array(genres.map { g -> JsonObject().apply { addProperty("name", g) } }))
            put("coverArt", coverArt)
            put("size", Json.long(source, "Size"))
            put("contentType", mimeFor(container))
            put("suffix", container)
            put("duration", seconds(s))
            put("bitRate", bitrate)
            put("samplingRate", Json.int(stream, "SampleRate"))
            put("bitDepth", Json.int(stream, "BitDepth"))
            put("channelCount", Json.int(stream, "Channels"))
            // Drift: Subsonic-style relative path (only used to name files)
            put("path", listOfNotNull(albumArtist ?: artistName, Json.str(s, "Album"),
                listOfNotNull(track?.let { String.format(Locale.ROOT, "%02d", it) }, title).joinToString(" - ")
                    + (container?.let { ".$it" } ?: "")).joinToString("/"))
            addProperty("isVideo", false)
            put("userRating", userRating(s))
            put("playCount", Json.long(ud, "PlayCount"))
            put("played", date(Json.str(ud, "LastPlayedDate")))
            put("discNumber", Json.int(s, "ParentIndexNumber"))
            put("created", date(Json.str(s, "DateCreated")))
            put("starred", starredDate(s))
            put("albumId", albumId)
            put("artistId", artists.firstNotNullOfOrNull { Json.str(it, "Id") } ?: albumArtists.firstNotNullOfOrNull { Json.str(it, "Id") })
            addProperty("type", "music")
            addProperty("mediaType", "song")
            put("displayArtist", artistName)
            put("displayAlbumArtist", albumArtist)
            add("artists", Json.array(artists.map { idName(it) }))
            add("albumArtists", Json.array(albumArtists.map { idName(it) }))
            put("sortName", Json.str(s, "SortName"))
            put("musicBrainzId", Json.str(Json.obj(s, "ProviderIds"), "MusicBrainzTrack"))
            // Drift: Jellyfin computes a normalization gain (LUFS) per track
            Json.double(s, "NormalizationGain")?.let { g ->
                add("replayGain", JsonObject().apply { addProperty("trackGain", g) })
            }
        }
    }

    private fun mimeFor(container: String?): String? = when (container) {
        null -> null
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4", "m4b", "alac" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "wma", "asf" -> "audio/x-ms-wma"
        "ape" -> "audio/ape"
        "wv" -> "audio/x-wavpack"
        "dsf", "dff" -> "audio/dsd"
        else -> "audio/$container"
    }

    // ---------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------

    private fun url(segments: List<String>, vararg query: Pair<String, String?>): HttpUrl {
        val b = base.newBuilder()
        segments.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun items(vararg query: Pair<String, String?>): List<JsonObject> {
        val u = if (emby) url(listOf("Users", uid, "Items"), *query)
        else url(listOf("Items"), "userId" to uid, *query)
        return Json.objects(get(u)?.asJsonObject, "Items")
    }

    private fun item(id: String, fields: String): JsonObject {
        val f = fields.takeIf { it.isNotEmpty() }
        val u = if (emby) url(listOf("Users", uid, "Items", id), "Fields" to f)
        else url(listOf("Items", id), "userId" to uid, "Fields" to f)
        return get(u)?.asJsonObject ?: throw HttpError(404, "item not found")
    }

    private fun get(url: HttpUrl): JsonElement? = send("GET", url)

    private fun send(method: String, url: HttpUrl, body: JsonElement? = null): JsonElement? {
        val rb = Request.Builder().url(url)
            // Drift: no OkHttp cache for internal calls: the data changes (favorites, plays)
            .cacheControl(NO_STORE)
            .header("Accept", "application/json")
        JellyfinClientInfo.authHeaders(credential).forEach { (k, v) -> rb.header(k, v) }
        val requestBody = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        rb.method(method, requestBody)
        fetch(rb.build()).use { r ->
            if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code} on ${url.encodedPath}")
            val text = r.body.string()
            if (text.isBlank()) return null
            return try {
                JsonParser.parseString(text)
            } catch (e: Exception) {
                null
            }
        }
    }

    private class Params(private val url: HttpUrl) {
        fun str(name: String): String? = url.queryParameter(name)?.takeIf { it.isNotEmpty() }
        fun int(name: String): Int? = str(name)?.toIntOrNull()
        fun all(name: String): List<String> = url.queryParameterValues(name).filterNotNull()
        fun required(name: String): String = str(name) ?: throw HttpError(400, "missing parameter: $name")
    }

    companion object {
        const val SUBSONIC_VERSION = "1.16.1"
        private const val EPOCH = "1970-01-01T00:00:00.000Z"
        private val JSON = "application/json".toMediaType()
        private val NO_STORE = CacheControl.Builder().noStore().noCache().build()

        const val SONG_FIELDS = "MediaSources,Genres,DateCreated,ParentId,SortName,ProviderIds"
        const val ALBUM_FIELDS = "Genres,DateCreated,ChildCount,SortName,ProviderIds"

        private val DATE = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?$")

        /** Jellyfin dates (up to 7 decimals) in the format EmptyDateTypeAdapter reads. */
        @JvmStatic
        fun date(value: String?): String? {
            if (value.isNullOrEmpty() || value.startsWith("0001-")) return null
            val m = DATE.matchEntire(value) ?: return null
            val millis = (m.groupValues[2] + "000").take(3)
            return "${m.groupValues[1]}.${millis}Z"
        }
    }
}
