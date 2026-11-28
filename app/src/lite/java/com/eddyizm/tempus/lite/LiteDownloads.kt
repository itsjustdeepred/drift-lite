package com.eddyizm.tempus.lite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Drift Lite: a single queue for songs to save on the phone (playlists, ⋮, followed). Two downloads at a time;
 * each song can be cancelled, failed ones can be retried. [items] changes at every step (the Offline tab observes it).
 */
object LiteDownloads {
    enum class S { QUEUED, SEARCHING, DOWNLOADING, DONE, NOT_FOUND, FAILED, CANCELLED }
    data class Item(val id: String, val state: S, val progress: Float = 0f, val order: Long = System.nanoTime()) {
        val pending get() = state == S.QUEUED || state == S.SEARCHING || state == S.DOWNLOADING
    }

    private val pool = Executors.newFixedThreadPool(2)
    private val map = ConcurrentHashMap<String, Item>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private fun set(id: String, s: S, p: Float = 0f) {
        val old = map[id]
        map[id] = Item(id, s, p, old?.order ?: System.nanoTime())
        publish()
    }

    private var lastPublish = 0L
    private fun publish(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 250) return
        lastPublish = now
        _items.value = map.values.sortedBy { it.order }
    }

    fun state(id: String): Item? = map[id]

    /** Queues the songs not yet on the phone; returns how many were added. */
    @JvmStatic
    fun enqueue(ids: List<String>): Int {
        var n = 0
        for (id in ids.distinct()) {
            if (LiteTracks.isDownloaded(id) || map[id]?.pending == true) continue
            cancelled.remove(id)
            set(id, S.QUEUED)
            n++
            pool.execute { run(id) }
        }
        return n
    }

    private fun run(id: String) {
        if (id in cancelled) return
        set(id, S.SEARCHING)
        if (!LiteTracks.findVideo(id)) { set(id, if (id in cancelled) S.CANCELLED else S.NOT_FOUND); return }
        if (id in cancelled) { set(id, S.CANCELLED); return }
        set(id, S.DOWNLOADING)
        val ok = LiteTracks.download(id, cancelled = { id in cancelled }) { p ->
            map[id] = Item(id, S.DOWNLOADING, p, map[id]?.order ?: System.nanoTime()); publish(force = false)
        }
        set(id, when { ok -> S.DONE; id in cancelled -> S.CANCELLED; else -> S.FAILED }, if (ok) 100f else 0f)
    }

    fun cancel(id: String) {
        cancelled.add(id)
        if (map[id]?.state == S.QUEUED) set(id, S.CANCELLED)
    }

    fun cancelAll() = map.values.filter { it.pending }.forEach { cancel(it.id) }

    fun retryFailed() = enqueue(map.values.filter { it.state == S.FAILED || it.state == S.NOT_FOUND || it.state == S.CANCELLED }.map { it.id })

    /** Removes finished items from the list (done, failed, cancelled). */
    fun clearFinished() { map.values.filter { !it.pending }.forEach { map.remove(it.id) }; publish() }
}
