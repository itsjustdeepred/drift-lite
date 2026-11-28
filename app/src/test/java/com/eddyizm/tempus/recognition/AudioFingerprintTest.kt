package com.eddyizm.tempus.recognition

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Live check against the recognition service: set DRIFT_PCM to a raw s16le 16 kHz mono file of a known song. */
class AudioFingerprintTest {
    @Test
    fun recognizesPcm() {
        val path = System.getenv("DRIFT_PCM")
        assumeTrue(path != null)
        val bytes = File(path!!).readBytes()
        val pcm = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val gap = (System.getenv("DRIFT_GAP") ?: "0").toLong()
        val t0 = System.currentTimeMillis()
        for (secs in (System.getenv("DRIFT_SECS") ?: "3,5,8,12").split(",").map { it.toInt() }) {
            val sig = AudioFingerprint().apply { feed(pcm, 0, minOf(pcm.size, secs * 16000)) }
            val r = runCatching { runBlocking { SongRecognizer.query(sig) } }
            println("RESULT t=${System.currentTimeMillis() - t0}ms ${secs}s peaks=${sig.peakCount} -> ${r.getOrNull()?.title ?: r.exceptionOrNull()?.javaClass?.simpleName ?: "no match"}")
            Thread.sleep(gap)
        }
    }
}
