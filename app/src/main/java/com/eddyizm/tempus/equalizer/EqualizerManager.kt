package com.eddyizm.tempus.equalizer

import android.content.Context

class EqualizerManager(
    private var backend: EqualizerBackend,
    private var context: Context
) {

    fun attach(audioSessionId: Int): Boolean {
        return backend.attach(audioSessionId, context.applicationContext)
    }

    fun release(audioSessionId: Int) {
        backend.release(audioSessionId, context.applicationContext)
    }

    fun setBandLevel(band: Short, level: Short) = backend.setBandLevel(band, level)
    fun getNumberOfBands(): Short = backend.getNumberOfBands()
    fun getBandLevelRange(): ShortArray? = backend.getBandLevelRange()
    fun getCenterFreq(band: Short): Int? = backend.getCenterFreq(band)
    fun getBandLevel(band: Short): Short? = backend.getBandLevel(band)
    fun setEnabled(enabled: Boolean) = backend.setEnabled(enabled)
    // Drift: reapplies the saved state (profile of the audio output that just changed).
    fun reapply() = backend.reapply(context.applicationContext)
}