package com.eddyizm.tempus.equalizer

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.util.Preferences
import com.google.gson.Gson

// Drift: equalizer profiles per audio output (car, each Bluetooth headphone/speaker, wired,
// phone speaker). The "active" state stays in the usual preferences read by the backends; here that
// state is saved into the profile of the output being left and the new output's profile is loaded.

enum class OutputKind { CAR, BLUETOOTH, WIRED, SPEAKER, REMOTE }

data class AudioOutput(val key: String, val kind: OutputKind, val name: String)

// Drift: nullable fields because Gson ignores Kotlin defaults.
// @Keep: R8 would rename the fields and the saved JSON could not be read back after an update.
@Keep
class EqState(
    var enabled: Boolean? = null,
    var bands: String? = null,
    var parametric: String? = null,
    var parametricName: String? = null
)

@Keep
class OutputEntry(
    var name: String? = null,
    var kind: String? = null,
    var separate: Boolean? = null,
    var state: EqState? = null
)

@Keep
class OutputProfileStore(
    var defaultState: EqState? = null,
    var outputs: MutableMap<String, OutputEntry>? = null,
    // Drift: which profile the active state in the preferences belongs to (null = default).
    var activeSlot: String? = null
)

class OutputEqController(
    context: Context,
    /** Renderer name when the queue plays off the phone (UPnP/Cast), otherwise null. */
    private val remoteOutputName: () -> String?,
    private val isCarConnected: () -> Boolean,
    /** applied = true if the equalizer state changed and must be reapplied. */
    private val onChanged: (applied: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "OutputEqController"
        const val DEFAULT_SLOT = "default"
        private const val DEBOUNCE_MS = 250L

        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
        )
        private val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_HDMI
        )
    }

    private val appContext = context.applicationContext
    private val speaker = AudioOutput("speaker", OutputKind.SPEAKER, appContext.getString(R.string.drift_eq_output_speaker))
    private val wired = AudioOutput("wired", OutputKind.WIRED, appContext.getString(R.string.drift_eq_output_wired))
    private val car = AudioOutput("car", OutputKind.CAR, appContext.getString(R.string.drift_eq_output_car))
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val store: OutputProfileStore = load()
    private var started = false

    /** Output in use, null when the feature is off. */
    var current: AudioOutput? = null
        private set

    private val evaluateRunnable = Runnable { evaluate() }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = scheduleEvaluate()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = scheduleEvaluate()
    }

    // Drift: kept in a field, SharedPreferences only holds weak references.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Preferences.EQ_PER_DEVICE) handler.post { onFeatureToggled() }
    }

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        App.getInstance().preferences.registerOnSharedPreferenceChangeListener(prefListener)
        evaluate()
    }

    fun release() {
        if (!started) return
        started = false
        handler.removeCallbacks(evaluateRunnable)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        App.getInstance().preferences.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    /** Re-evaluates the output shortly after: changes arrive in bursts (route, controllers, devices). */
    fun scheduleEvaluate() {
        if (!started) return
        handler.removeCallbacks(evaluateRunnable)
        handler.postDelayed(evaluateRunnable, DEBOUNCE_MS)
    }

    fun isEnabled(): Boolean = Preferences.isEqPerDeviceEnabled()

    /** Is the current output's separate profile active? */
    fun isCurrentSeparate(): Boolean {
        val out = current ?: return false
        return entryFor(out).separate ?: defaultSeparate(out.kind)
    }

    fun setCurrentSeparate(separate: Boolean) {
        val out = current ?: return
        if (out.kind == OutputKind.REMOTE) return
        entryFor(out).separate = separate
        persist()
        evaluate()
    }

    /** Saved separate profiles: key and display name. */
    fun savedProfiles(): List<Pair<String, String>> =
        outputs().filter { it.value.separate == true && it.value.state != null || it.key == store.activeSlot }
            .filter { it.key != DEFAULT_SLOT }
            .map { it.key to (it.value.name ?: it.key) }
            .sortedBy { it.second.lowercase() }

    fun deleteProfile(key: String) {
        if (store.activeSlot == key) {
            // Drift: the active state belonged to this profile, go back to the default without saving it.
            loadSlot(DEFAULT_SLOT)
            store.activeSlot = DEFAULT_SLOT
        }
        outputs().remove(key)
        persist()
        onChanged(true)
        evaluate()
    }

    private fun onFeatureToggled() {
        if (isEnabled()) {
            evaluate()
            return
        }
        // Drift: feature off, back to the single equalizer (the default profile).
        handler.removeCallbacks(evaluateRunnable)
        val applied = switchTo(DEFAULT_SLOT)
        if (applied) persist()
        current = null
        onChanged(applied)
    }

    private fun evaluate() {
        if (!started) return
        if (!isEnabled()) {
            if (current != null) {
                current = null
                onChanged(false)
            }
            return
        }
        val out = detect()
        val changed = out != current
        current = out
        if (changed) Log.d(TAG, "Audio output: ${out.kind} ${out.name}")
        if (out.kind == OutputKind.REMOTE) {
            // Drift: the phone's equalizer does not reach the renderer, the state is left as is.
            if (changed) onChanged(false)
            return
        }
        val entry = entryFor(out)
        val isNew = entry.kind == null
        entry.name = out.name
        entry.kind = out.kind.name
        if (entry.separate == null) entry.separate = defaultSeparate(out.kind)
        val target = if (entry.separate == true) out.key else DEFAULT_SLOT
        val applied = switchTo(target)
        if (applied || isNew) persist()
        if (applied || changed) onChanged(applied)
    }

    /** Saves the active state into its profile and loads [target]'s. true if the profile changed. */
    private fun switchTo(target: String): Boolean {
        val active = store.activeSlot ?: DEFAULT_SLOT
        if (active == target) return false
        saveActiveInto(active)
        loadSlot(target)
        store.activeSlot = target
        return true
    }

    private fun saveActiveInto(slot: String) {
        val state = EqState(
            Preferences.isEqualizerEnabled(),
            Preferences.getEqualizerBandLevelsRaw(),
            Preferences.getParametricEqProfile(),
            Preferences.getParametricEqName()
        )
        if (slot == DEFAULT_SLOT) store.defaultState = state
        else outputs()[slot]?.state = state
    }

    private fun loadSlot(slot: String) {
        val state = if (slot == DEFAULT_SLOT) {
            store.defaultState
        } else {
            val entry = outputs()[slot]
            // Drift: an output without a profile starts from a copy of the default.
            entry?.state ?: store.defaultState?.copy()?.also { entry?.state = it }
        } ?: return
        Preferences.setActiveEqualizerState(
            state.enabled ?: false, state.bands, state.parametric, state.parametricName
        )
    }

    private fun EqState.copy() = EqState(enabled, bands, parametric, parametricName)

    private fun defaultSeparate(kind: OutputKind) = kind == OutputKind.CAR || kind == OutputKind.BLUETOOTH

    private fun outputs(): MutableMap<String, OutputEntry> =
        store.outputs ?: mutableMapOf<String, OutputEntry>().also { store.outputs = it }

    private fun entryFor(out: AudioOutput): OutputEntry = outputs().getOrPut(out.key) { OutputEntry() }

    private fun load(): OutputProfileStore = try {
        Preferences.getEqOutputProfilesJson()?.let { gson.fromJson(it, OutputProfileStore::class.java) }
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable equalizer profiles, starting from scratch", e)
        null
    } ?: OutputProfileStore()

    private fun persist() {
        Preferences.setEqOutputProfilesJson(gson.toJson(store))
    }

    // Drift: output detection. Order: network renderer, Android Auto, actual media route
    // (API 33+), then Android's own priority (Bluetooth > wired > speaker).
    private fun detect(): AudioOutput {
        remoteOutputName()?.let { return AudioOutput("remote", OutputKind.REMOTE, it) }
        if (isCarConnected()) return car
        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (e: Exception) {
            emptyList()
        }
        routedMediaDevice(devices)?.let { return classify(it) }
        devices.firstOrNull { it.type in BLUETOOTH_TYPES }?.let { return classify(it) }
        devices.firstOrNull { it.type in WIRED_TYPES }?.let { return classify(it) }
        return speaker
    }

    private fun routedMediaDevice(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val routed = audioManager.getAudioDevicesForAttributes(attrs).firstOrNull() ?: return null
            devices.firstOrNull { it.type == routed.type && it.address == routed.address }
                ?: devices.firstOrNull { it.type == routed.type }
        } catch (e: Exception) {
            null
        }
    }

    private fun classify(device: AudioDeviceInfo): AudioOutput = when (device.type) {
        in BLUETOOTH_TYPES -> {
            // Drift: productName does not need BLUETOOTH_CONNECT; the address (even anonymized)
            // tells apart two devices with the same name.
            val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            val address = device.address.orEmpty()
            AudioOutput("bt:$address|$name", OutputKind.BLUETOOTH, name)
        }
        in WIRED_TYPES -> wired
        AudioDeviceInfo.TYPE_BUS -> car
        else -> speaker
    }
}
