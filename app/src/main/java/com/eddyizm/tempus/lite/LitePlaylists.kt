package com.eddyizm.tempus.lite

import com.eddyizm.tempus.App
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Drift Lite: your playlists, on the phone. The list order is the displayed one (reordered by hand),
 * songs are "yt:..." ids from the [LiteTracks] index. Thread-safe; writes to disk on every change.
 */
object LitePlaylists {
    data class P(val id: String, val name: String, val tracks: List<String>, val updated: Long = System.currentTimeMillis())

    private val gson = Gson()
    private val file get() = File(File(App.getContext().filesDir, "lite").apply { mkdirs() }, "playlists.json")
    private var cache: MutableList<P>? = null

    @Synchronized
    private fun list(): MutableList<P> = cache ?: (runCatching {
        gson.fromJson<MutableList<P?>>(file.readText(), object : TypeToken<MutableList<P?>>() {}.type)
            ?.filter { valid(it) }?.map { it!! }?.toMutableList()
    }.getOrNull() ?: mutableListOf()).also { cache = it }

    @Suppress("SENSELESS_COMPARISON")
    private fun valid(p: P?) = p != null && p.id != null && p.name != null && p.tracks != null && p.tracks.all { it != null }

    @Synchronized
    private fun save() { runCatching { file.writeText(gson.toJson(list())) } }

    @Synchronized fun all(): List<P> = list().toList()
    @Synchronized fun get(id: String): P? = list().firstOrNull { it.id == id }

    @Synchronized
    fun create(name: String, tracks: List<String> = emptyList()): String {
        val p = P(UUID.randomUUID().toString().take(12), name.trim().ifEmpty { App.getContext().getString(com.eddyizm.tempus.R.string.lite_default_playlist_name) }, tracks.distinct())
        list().add(0, p); save()
        return p.id
    }

    @Synchronized
    private fun update(id: String, f: (P) -> P) {
        val l = list(); val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        l[i] = f(l[i]).copy(updated = System.currentTimeMillis()); save()
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    @Synchronized fun delete(id: String) { list().removeAll { it.id == id }; save() }

    /** Appends the songs not already there; returns how many were added. */
    @Synchronized
    fun add(id: String, tracks: List<String>): Int {
        val p = get(id) ?: return 0
        val new = tracks.filter { it !in p.tracks }.distinct()
        if (new.isNotEmpty()) update(id) { it.copy(tracks = it.tracks + new) }
        return new.size
    }

    fun removeAt(id: String, index: Int) = update(id) { p -> p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index }) }

    fun moveTrack(id: String, from: Int, to: Int) = update(id) { p ->
        if (from !in p.tracks.indices || to !in p.tracks.indices) p
        else p.copy(tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) })
    }

    @Synchronized
    fun movePlaylist(from: Int, to: Int) {
        val l = list()
        if (from !in l.indices || to !in l.indices) return
        l.add(to, l.removeAt(from)); save()
    }

    // backup
    @Synchronized fun exportJson(): String = gson.toJson(list())
    @Synchronized
    fun importJson(json: String) {
        val incoming: List<P> = (gson.fromJson<List<P?>>(json, object : TypeToken<List<P?>>() {}.type) ?: return).filter { valid(it) }.map { it!! }
        val l = list()
        incoming.forEach { p -> val i = l.indexOfFirst { it.id == p.id }; if (i >= 0) l[i] = p else l.add(p) }
        save()
    }
}
