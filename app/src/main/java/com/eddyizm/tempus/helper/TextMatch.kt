package com.eddyizm.tempus.helper

/** Normalization to compare titles and artists across Spotify/Deezer, YouTube and Navidrome. */
object TextMatch {
    private val BRACKETS = Regex("""\s*[(\[][^)\]]*(feat|ft\.|with|remaster|version|versione|edit|live|mono|stereo|from|prod)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val DASH_SUFFIX = Regex("""\s+-\s+.*(remaster|version|edit|live|mix|from|radio).*$""", RegexOption.IGNORE_CASE)
    private val FEAT = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)

    fun norm(s: String): String {
        var x = BRACKETS.replace(s, "")
        x = DASH_SUFFIX.replace(x, "")
        x = FEAT.replace(x, "")
        x = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return x.replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    fun artists(s: String): List<String> =
        s.split(Regex(""",|&| x | e | and | feat\.? | ft\.? """, RegexOption.IGNORE_CASE)).map { norm(it) }.filter { it.isNotEmpty() }
}
