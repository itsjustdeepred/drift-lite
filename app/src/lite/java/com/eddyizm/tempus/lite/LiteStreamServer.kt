package com.eddyizm.tempus.lite

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Drift Lite: a tiny HTTP server on the local network, so speakers (Sonos, DLNA) can play YouTube songs.
 * The speaker asks for http://<phone>:<port>/t/<id>?format=m4a; the phone resolves the AAC stream
 * (Sonos does not play Opus) and relays it, forwarding Range requests so seeking works.
 */
object LiteStreamServer {
    private const val TAG = "LiteStreamServer"
    @Volatile private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    /** Address a renderer on the LAN can fetch for this song, or null without a LAN address. */
    @JvmStatic
    fun urlFor(id: String): String? {
        val ip = lanAddress() ?: return null
        val port = ensureStarted() ?: return null
        return "http://$ip:$port/t/${id.removePrefix(LiteTracks.PREFIX)}?format=m4a"
    }

    @Synchronized
    private fun ensureStarted(): Int? {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        return try {
            val s = ServerSocket(0)
            socket = s
            Thread({ acceptLoop(s) }, "lite-stream").apply { isDaemon = true }.start()
            s.localPort
        } catch (e: Exception) { Log.w(TAG, "start", e); null }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: continue
            pool.execute { runCatching { handle(c) }.onFailure { Log.w(TAG, "request", it) }; runCatching { c.close() } }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
        val request = reader.readLine() ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val parts = request.split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1)?.substringBefore('?') ?: return
        val out = c.getOutputStream()
        val key = path.removePrefix("/t/")
        val audio = if (path.startsWith("/t/") && key.matches(Regex("[0-9a-f]{8,40}"))) LiteTracks.audioFor(LiteTracks.PREFIX + key, preferAac = true) else null
        if (audio == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
        }
        val upstream = okhttp3.Request.Builder().url(audio.url).header("User-Agent", LiteYouTube.UA)
            .apply { headers["range"]?.let { header("Range", it) } }.build()
        LiteYouTube.http.newCall(upstream).execute().use { r ->
            val sb = StringBuilder("HTTP/1.1 ${r.code} ${if (r.code == 206) "Partial Content" else "OK"}\r\n")
            sb.append("Content-Type: ").append(audio.mime).append("\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            r.header("Content-Length")?.let { sb.append("Content-Length: ").append(it).append("\r\n") }
            r.header("Content-Range")?.let { sb.append("Content-Range: ").append(it).append("\r\n") }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            if (method != "HEAD") r.body.byteStream().use { it.copyTo(out, 64 * 1024) }
            out.flush()
        }
    }

    /** IPv4 address of the phone on Wi-Fi/Ethernet (site-local). */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
