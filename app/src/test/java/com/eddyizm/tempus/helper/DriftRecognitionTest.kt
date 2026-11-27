package com.eddyizm.tempus.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftRecognitionTest {
    @Test
    fun foundInLibrary() {
        val r = DriftRecognition.parse("""{"status":"found","provider":"shazam","title":"Blinding Lights","artist":"The Weeknd",
            "album":"After Hours","cover":"http://c","isrc":"USUG11904206","duration":200,"song_id":"abc","deezer_id":908604612}""")
        val s = (r as DriftRecognition.Result.Found).song
        assertEquals("Blinding Lights", s.title)
        assertEquals("abc", s.songId)
        assertEquals(908604612L, s.deezerId)
    }

    @Test
    fun foundWithNulls() {
        val r = DriftRecognition.parse("""{"status":"found","title":"T","artist":"A","album":null,"cover":null,"isrc":null,"song_id":null,"deezer_id":null}""")
        val s = (r as DriftRecognition.Result.Found).song
        assertNull(s.songId); assertNull(s.deezerId); assertNull(s.album)
    }

    @Test
    fun notFoundAndErrors() {
        assertTrue(DriftRecognition.parse("""{"status":"not_found"}""") is DriftRecognition.Result.NotFound)
        assertTrue(DriftRecognition.parse("""{"status":"error","message":"song recognition is disabled"}""") is DriftRecognition.Result.Disabled)
        assertTrue(DriftRecognition.parse("""{"status":"error","reason":"bad_audio","message":"x"}""") is DriftRecognition.Result.Failed)
        assertTrue(DriftRecognition.parse("not json") is DriftRecognition.Result.Failed)
    }
}
