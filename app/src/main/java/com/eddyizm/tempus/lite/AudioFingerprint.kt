package com.eddyizm.tempus.lite

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Audio fingerprint computed on the phone from 16 kHz mono 16-bit PCM (algorithm from the open-source
 * SongRec project): a 2048-point FFT every 128 samples, spectral peaks kept per frequency band.
 */
class AudioFingerprint {
    companion object {
        const val RATE = 16_000
        private const val BINS = 1025
        private const val RING = 256
        private val WINDOW = DoubleArray(2048) { n -> 0.5 - 0.5 * cos(2 * PI * (n + 1) / 2049) }
        private val NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val OTHER_FFTS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)
        private val COS = DoubleArray(1024) { cos(-2 * PI * it / 2048) }
        private val SIN = DoubleArray(1024) { sin(-2 * PI * it / 2048) }
        private val REV = IntArray(2048) { Integer.reverse(it) ushr 21 }
    }

    private class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    private val samples = ShortArray(2048)
    private var samplesPos = 0
    private val fft = Array(RING) { FloatArray(BINS) }
    private var fftPos = 0
    private val spread = Array(RING) { FloatArray(BINS) }
    private var spreadPos = 0
    private var spreadWritten = 0
    private val bands = java.util.TreeMap<Int, MutableList<Peak>>()
    private val pending = ShortArray(128)
    private var pendingLen = 0
    private val re = DoubleArray(2048)
    private val im = DoubleArray(2048)

    /** Samples analysed so far. */
    var sampleCount = 0
        private set

    val peakCount get() = bands.values.sumOf { it.size }

    fun feed(pcm: ShortArray, from: Int = 0, to: Int = pcm.size) {
        for (i in from until to) {
            pending[pendingLen++] = pcm[i]
            if (pendingLen == 128) { process(pending); pendingLen = 0 }
        }
    }

    private fun process(chunk: ShortArray) {
        sampleCount += 128
        System.arraycopy(chunk, 0, samples, samplesPos, 128)
        samplesPos = (samplesPos + 128) % 2048
        doFft()
        doSpread()
        if (spreadWritten >= 46) findPeaks()
    }

    private fun doFft() {
        for (i in 0 until 2048) {
            val j = REV[i]
            re[j] = samples[(samplesPos + i) % 2048] * WINDOW[i]
            im[j] = 0.0
        }
        var size = 2
        while (size <= 2048) {
            val half = size / 2; val step = 2048 / size
            var start = 0
            while (start < 2048) {
                for (k in 0 until half) {
                    val c = COS[k * step]; val s = SIN[k * step]
                    val a = start + k; val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += size
            }
            size *= 2
        }
        val out = fft[fftPos]
        for (i in 0 until BINS) out[i] = max((re[i] * re[i] + im[i] * im[i]) / (1 shl 17), 1e-10).toFloat()
        fftPos = (fftPos + 1) % RING
    }

    private fun doSpread() {
        val last = fft[(fftPos - 1 + RING) % RING]
        val cur = spread[spreadPos]
        System.arraycopy(last, 0, cur, 0, BINS)
        for (i in 0..1022) cur[i] = max(max(cur[i], cur[i + 1]), cur[i + 2])
        for (former in intArrayOf(1, 3, 6)) {
            val f = spread[(spreadPos - former + RING) % RING]
            for (i in 0 until BINS) if (cur[i] > f[i]) f[i] = cur[i]
        }
        spreadPos = (spreadPos + 1) % RING
        spreadWritten++
    }

    private fun mag(v: Float) = ln(max(1.0 / 64, v.toDouble())) * 1477.3 + 6144

    private fun findPeaks() {
        val f46 = fft[(fftPos - 46 + RING) % RING]
        val s49 = spread[(spreadPos - 49 + RING) % RING]
        for (bin in 10 until 1015) {
            val v = f46[bin]
            if (v < 1f / 64 || v < s49[bin - 1]) continue
            var maxNeighbor = 0f
            for (o in NEIGHBORS) maxNeighbor = max(maxNeighbor, s49[bin + o])
            if (v <= maxNeighbor) continue
            var maxOther = maxNeighbor
            for (o in OTHER_FFTS) maxOther = max(maxOther, spread[((spreadPos + o) % RING + RING) % RING][bin - 1])
            if (v <= maxOther) continue

            val m = mag(v); val before = mag(f46[bin - 1]); val after = mag(f46[bin + 1])
            val v1 = m * 2 - before - after
            if (v1 <= 0) continue
            val corrected = bin * 64 + (after - before) * 32 / v1
            val hz = corrected * (RATE / 2.0 / 1024 / 64)
            val band = when {
                hz > 250 && hz < 520 -> 0
                hz > 520 && hz < 1450 -> 1
                hz > 1450 && hz < 3500 -> 2
                hz > 3500 && hz <= 5500 -> 3
                else -> continue
            }
            bands.getOrPut(band) { mutableListOf() }.add(Peak(spreadWritten - 46, m.toInt(), corrected.toInt()))
        }
    }

    /** The signature as a data URI, ready for the recognition request. */
    fun toUri(): String = "data:audio/vnd.shazam.sig;base64," + base64(toBinary())

    // java.util.Base64 needs API 26, android.util.Base64 is missing in unit tests
    private fun base64(b: ByteArray): String {
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((b.size + 2) / 3 * 4)
        var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i)
            val v = ((b[i].toInt() and 0xff) shl 16) or ((if (n > 1) b[i + 1].toInt() and 0xff else 0) shl 8) or (if (n > 2) b[i + 2].toInt() and 0xff else 0)
            sb.append(abc[v shr 18 and 63]).append(abc[v shr 12 and 63])
            sb.append(if (n > 1) abc[v shr 6 and 63] else '=').append(if (n > 2) abc[v and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun toBinary(): ByteArray {
        val contents = ByteArrayOutputStream()
        fun le32(o: ByteArrayOutputStream, v: Int) = o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun le16(o: ByteArrayOutputStream, v: Int) = o.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        for ((band, peaks) in bands) {
            val p = ByteArrayOutputStream()
            var pass = 0
            for (peak in peaks) {
                if (peak.pass - pass >= 255) { p.write(0xff); le32(p, peak.pass); pass = peak.pass }
                p.write(peak.pass - pass)
                le16(p, peak.magnitude)
                le16(p, peak.bin)
                pass = peak.pass
            }
            le32(contents, 0x60030040 + band)
            le32(contents, p.size())
            contents.write(p.toByteArray())
            repeat((4 - p.size() % 4) % 4) { contents.write(0) }
        }
        val body = contents.toByteArray()
        val buf = ByteBuffer.allocate(48 + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0xcafe2580.toInt()).putInt(0).putInt(body.size + 8).putInt(0x94119c00.toInt())
        buf.putInt(0).putInt(0).putInt(0)
        buf.putInt(3 shl 27) // 16 kHz
        buf.putInt(0).putInt(0)
        buf.putInt((sampleCount + RATE * 0.24).toInt())
        buf.putInt((15 shl 19) + 0x40000)
        buf.putInt(0x40000000).putInt(body.size + 8)
        buf.put(body)
        val out = buf.array()
        val crc = CRC32().apply { update(out, 8, out.size - 8) }.value.toInt()
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc)
        return out
    }
}
