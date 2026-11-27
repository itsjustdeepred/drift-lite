package com.eddyizm.tempus.lite

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/**
 * Drift Lite: song recognition, all on the phone. Recording starts the moment the button is pressed;
 * the fingerprint of what has been heard so far is sent after 3 seconds and twice more while listening
 * continues (the service allows about three quick queries), until a match or [MAX_MS]. Cancelling the coroutine stops everything.
 */
object LiteRecognizer {
    data class Song(val title: String, val artist: String, val album: String?, val cover: String?, val isrc: String?)

    /** Progress for the UI: [level] 0..1 microphone loudness, [searching] true once a query has been sent. */
    data class Progress(val elapsedMs: Long, val level: Float, val searching: Boolean)

    class NoNetwork : Exception()
    /** The service asks to slow down (HTTP 429). */
    class Busy : IOException()

    private const val MAX_MS = 15_000L
    private const val WINDOW_S = 12
    /** When to send a query, in ms of audio heard. */
    private val CHECKPOINTS = longArrayOf(3_000, 7_000, 12_000)

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()

    /** Listens and returns the song, or null when nothing matched within [MAX_MS]. Throws [NoNetwork] or mic errors. */
    @SuppressLint("MissingPermission")
    suspend fun listen(onProgress: (Progress) -> Unit): Song? = coroutineScope {
        val rate = AudioFingerprint.RATE
        val pcm = ShortArray(rate * (MAX_MS / 1000).toInt() + rate)
        var written = 0
        var level = 0f
        val lock = Any()

        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("mic") }
        rec.startRecording()
        val recorder = launch(Dispatchers.IO) {
            val buf = ShortArray(1600)
            try {
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                    synchronized(lock) {
                        val room = minOf(n, pcm.size - written)
                        System.arraycopy(buf, 0, pcm, written, room)
                        written += room
                        // loudness in dB, -50 dBFS (quiet room) .. -10 dBFS (loud music) mapped to 0..1
                        val db = 20 * kotlin.math.log10(maxOf(sqrt(sum / n), 1.0) / 32768.0)
                        level = ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
                    }
                    if (written >= pcm.size) break
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }

        try {
            var searching = false
            val ticker = launch {
                while (isActive) {
                    val (w, l) = synchronized(lock) { written to level }
                    onProgress(Progress(w * 1000L / rate, l, searching))
                    delay(50)
                }
            }
            var result: Song? = null
            var failures = 0
            var answered = false
            var networkError = false
            for (cp in CHECKPOINTS) {
                while (synchronized(lock) { written } * 1000L / rate < cp) {
                    if (!recorder.isActive) break
                    delay(50)
                }
                val window = synchronized(lock) {
                    val from = maxOf(0, written - WINDOW_S * rate)
                    pcm.copyOfRange(from, written)
                }
                if (window.size < rate) break
                searching = true
                val sig = withContext(Dispatchers.Default) { AudioFingerprint().apply { feed(window) } }
                if (sig.peakCount == 0) continue
                try {
                    result = query(sig)
                    answered = true
                    failures = 0
                } catch (e: Busy) {
                    // rate limited: keep listening, the next query may go through
                } catch (e: IOException) {
                    networkError = true
                    if (++failures >= 2) throw NoNetwork()
                }
                if (result != null) break
            }
            ticker.cancel()
            if (result == null && !answered) throw if (networkError) NoNetwork() else Busy()
            result
        } finally {
            recorder.cancel()
        }
    }

    /** One recognition request with the given fingerprint. */
    suspend fun query(sig: AudioFingerprint): Song? {
        val body = JsonObject().apply {
            addProperty("timezone", TimeZone.getDefault().id)
            add("signature", JsonObject().apply {
                addProperty("uri", sig.toUri())
                addProperty("samplems", sig.sampleCount * 1000L / AudioFingerprint.RATE)
            })
            addProperty("timestamp", System.currentTimeMillis())
            add("context", JsonObject())
            add("geolocation", JsonObject())
        }
        val loc = Locale.getDefault()
        val lang = loc.language.ifEmpty { "en" }
        val country = loc.country.ifEmpty { "US" }
        val url = "https://amp.shazam.com/discovery/v5/$lang/$country/iphone/-/tag/${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID().toString().uppercase()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val req = Request.Builder().url(url)
            .header("Accept", "*/*").header("Accept-Language", lang)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP1A.240305.019)")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val text = http.newCall(req).await().use { r ->
            if (r.code == 429) throw Busy()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body.string()
        }
        return parse(text)
    }

    internal fun parse(json: String): Song? {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val matches = o.getAsJsonArray("matches")
        if (matches == null || matches.size() == 0) return null
        val t = o.getAsJsonObject("track") ?: return null
        fun JsonObject.s(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        val title = t.s("title") ?: return null
        val artist = t.s("subtitle") ?: return null
        val images = t.getAsJsonObject("images")
        val album = t.getAsJsonArray("sections")?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.s("type") == "SONG" }?.getAsJsonArray("metadata")?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.s("title") in setOf("Album", "Album:") }?.s("text")
        return Song(title, artist, album, images?.s("coverarthq") ?: images?.s("coverart"), t.s("isrc"))
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { c ->
        c.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = c.resume(response)
            override fun onFailure(call: Call, e: IOException) { if (!c.isCancelled) c.resumeWithException(e) }
        })
    }
}
