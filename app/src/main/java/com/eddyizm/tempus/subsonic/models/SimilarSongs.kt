package com.eddyizm.tempus.subsonic.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
class SimilarSongs {
    @SerializedName("song")
    var songs: List<Child>? = null
        get() = com.eddyizm.tempus.flavor.FlavorHooks.filterSongs(field)  // Drift: content exclusions (Undertow) apply to mixes
}