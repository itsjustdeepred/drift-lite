package com.eddyizm.tempus.subsonic.jellyfin

import com.eddyizm.tempus.subsonic.Subsonic
import com.eddyizm.tempus.subsonic.SubsonicPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drift: parts of the Jellyfin emulation that do not need a server. */
class JellyfinEmulationTest {

    private val cred = JellyfinCredential(ServerType.JELLYFIN, "ec60c219351d4144af493e67056e0fc4", "e2c16c39b9c54f7582dd55e68a3e771a")

    @Test
    fun credentialRoundTrip() {
        assertEquals(cred, JellyfinCredential.parse(cred.encode()))
        assertTrue(JellyfinCredential.parse(cred.copy(kind = ServerType.EMBY).encode())!!.isEmby)
        assertNull(JellyfinCredential.parse("hunter2"))
        assertNull(JellyfinCredential.parse("drift~plex~a~b"))
        assertNull(JellyfinCredential.parse(null))
        assertFalse(cred.toString().contains(cred.token))
    }

    @Test
    fun credentialPasswordGoesOutInClearAsP() {
        val prefs = SubsonicPreferences()
        prefs.serverUrl = "http://jf.local:8096"
        prefs.username = "drift"
        // token/salt from an old Subsonic login must not replace the Jellyfin session
        prefs.setAuthentication(cred.encode(), "oldtoken", "oldsalt", false)
        val params = Subsonic(prefs).params
        assertEquals(cred.encode(), params["p"])
        assertNull(params["t"])
        assertNull(params["s"])
    }

    @Test
    fun subsonicPasswordStillHashed() {
        val prefs = SubsonicPreferences()
        prefs.setAuthentication("secret", null, null, false)
        assertNull(prefs.authentication.password)
        assertTrue(prefs.authentication.token != null)
    }

    @Test
    fun targetKeepsServerPathPrefix() {
        val t = JellyfinTarget.parse("https://host.example/jellyfin/rest/getAlbum.view?u=x&p=${cred.encode()}&id=abc".toHttpUrl())!!
        assertEquals("getAlbum", t.method)
        assertEquals("https://host.example/jellyfin", t.base.toString())
        assertEquals(cred, t.credential)

        assertNull(JellyfinTarget.parse("https://host.example/rest/ping?u=x&p=plain".toHttpUrl()))
        assertNull(JellyfinTarget.parse("https://host.example/other/ping?p=${cred.encode()}".toHttpUrl()))
    }

    @Test
    fun mediaRewrites() {
        val p = cred.encode()
        val raw = JellyfinMedia.rewrite("http://jf:8096/rest/stream?u=x&p=$p&v=1&c=D&maxBitRate=0&format=raw&id=abc")!!
        assertEquals("http://jf:8096/Audio/abc/stream?static=true", raw.url.toString())
        assertTrue(raw.headers["Authorization"]!!.contains("Token=\"${cred.token}\""))

        val opus = JellyfinMedia.rewrite("http://jf:8096/rest/stream?u=x&p=$p&maxBitRate=128&format=opus&timeOffset=30&id=abc")!!
        assertEquals("/Audio/abc/universal", opus.url.encodedPath)
        assertEquals("128000", opus.url.queryParameter("MaxStreamingBitrate"))
        assertEquals("opus", opus.url.queryParameter("AudioCodec"))
        assertEquals("300000000", opus.url.queryParameter("StartTimeTicks"))

        val cover = JellyfinMedia.rewrite("http://jf:8096/rest/getCoverArt?u=x&p=$p&size=300&id=alb")!!
        assertEquals("/Items/alb/Images/Primary", cover.url.encodedPath)
        assertEquals("300", cover.url.queryParameter("maxWidth"))

        val emby = JellyfinMedia.rewrite("http://e:8096/emby/rest/download?u=x&p=${cred.copy(kind = ServerType.EMBY).encode()}&id=s1")!!
        assertEquals("/emby/Audio/s1/stream", emby.url.encodedPath)
        assertEquals(cred.token, emby.headers["X-Emby-Token"])

        assertNull(JellyfinMedia.rewrite("http://nav:4533/rest/stream?u=x&t=abc&s=def&id=1"))
    }

    @Test
    fun jellyfinDatesBecomeSubsonicDates() {
        assertEquals("2026-11-27T09:27:13.015Z", JellyfinTranslator.date("2026-11-27T09:27:13.0150227Z"))
        assertEquals("2026-11-27T09:27:13.000Z", JellyfinTranslator.date("2026-11-27T09:27:13Z"))
        assertEquals("2026-11-27T09:27:13.500Z", JellyfinTranslator.date("2026-11-27T09:27:13.5"))
        assertNull(JellyfinTranslator.date("0001-01-01T00:00:00.0000000Z"))
        assertNull(JellyfinTranslator.date(null))
    }
}
