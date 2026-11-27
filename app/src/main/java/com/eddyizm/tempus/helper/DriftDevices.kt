package com.eddyizm.tempus.helper

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.eddyizm.tempus.R
import com.eddyizm.tempus.upnp.UpnpRouteProvider
import com.eddyizm.tempus.util.Flavors
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Drift: Spotify-style "Play on". Phone and Bluetooth (LIVE_AUDIO), Chromecast and
 * Sonos/UPnP-DLNA renderers in a single list, in the app theme.
 *
 * Discovery runs in the background (passive) while the app is in the foreground, so the speakers
 * are already listed when the sheet opens; with the sheet open, discovery becomes active.
 */
object DriftDevices {

    private const val TAG = "drift_devices"

    @JvmStatic
    fun selector(context: Context): MediaRouteSelector {
        val b = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
        Flavors.addCastCategories(context, b)
        if (Preferences.isUpnpEnabled()) b.addControlCategory(UpnpRouteProvider.CATEGORY_UPNP)
        return b.build()
    }

    private val background = object : MediaRouter.Callback() {}
    private var backgroundOn = false

    /** From MainActivity.onStart: keeps device discovery alive while the app is visible. */
    @JvmStatic
    fun startBackgroundDiscovery(context: Context) {
        if (backgroundOn) return
        runCatching {
            MediaRouter.getInstance(context.applicationContext)
                .addCallback(selector(context.applicationContext), background, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            backgroundOn = true
        }
    }

    /** From MainActivity.onStop: no discovery while the app is closed (battery). */
    @JvmStatic
    fun stopBackgroundDiscovery(context: Context) {
        if (!backgroundOn) return
        runCatching { MediaRouter.getInstance(context.applicationContext).removeCallback(background) }
        backgroundOn = false
    }

    @JvmStatic
    fun show(activity: FragmentActivity) {
        val fm = activity.supportFragmentManager
        if (fm.findFragmentByTag(TAG) != null) return
        DevicesSheet().show(fm, TAG)
    }

    internal fun isRemote(r: MediaRouter.RouteInfo) = !r.isDefaultOrBluetooth

    private val BT_OUTPUTS = setOf(
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER, android.media.AudioDeviceInfo.TYPE_HEARING_AID
    )

    /** Names of the Bluetooth outputs connected right now; Samsung keeps the route listed even after disconnecting. */
    internal fun connectedBluetooth(context: Context): List<String> =
        runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in BT_OUTPUTS }.map { it.productName?.toString().orEmpty() }
        }.getOrDefault(emptyList())

    /** A Bluetooth route counts only if a real Bluetooth output exists, with the same name when known. */
    internal fun isPresent(r: MediaRouter.RouteInfo, connected: List<String>): Boolean =
        !r.isBluetooth || connected.any { it == r.name } || (connected.isNotEmpty() && connected.all { it.isBlank() })

    internal fun icon(r: MediaRouter.RouteInfo): Int = when {
        r.isDefault -> R.drawable.ic_drift_phone
        r.isBluetooth -> if (r.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER) R.drawable.ic_drift_speaker else R.drawable.ic_drift_headphones
        r.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_TV -> R.drawable.ic_drift_tv
        r.supportsControlCategory(UpnpRouteProvider.CATEGORY_UPNP) -> R.drawable.ic_drift_speaker
        r.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> R.drawable.ic_drift_speaker
        else -> R.drawable.ic_drift_cast
    }

    // "Kitchen - Sonos Era 100 Media Renderer" -> name "Kitchen", model "Sonos Era 100"
    private val UPNP_NAME = Regex("^(.+?)\\s+-\\s+(.+?)(?:\\s+Media Renderer)?$")

    internal fun name(ctx: Context, r: MediaRouter.RouteInfo): String =
        if (r.isDefault) ctx.getString(R.string.drift_devices_this_phone)
        else UPNP_NAME.find(r.name)?.groupValues?.get(1)?.takeIf { r.supportsControlCategory(UpnpRouteProvider.CATEGORY_UPNP) } ?: r.name

    internal fun kind(ctx: Context, r: MediaRouter.RouteInfo): String = when {
        r.isDefault -> ctx.getString(R.string.drift_devices_phone_speaker)
        r.isBluetooth -> "Bluetooth"
        r.supportsControlCategory(UpnpRouteProvider.CATEGORY_UPNP) ->
            UPNP_NAME.find(r.name)?.groupValues?.get(2)
                ?.substringBefore(" Media Renderer")?.substringBefore(" - RINCON")?.trim()?.ifBlank { null } ?: "Sonos / DLNA"
        else -> "Google Cast"
    }
}

class DevicesSheet : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DevicesContent(onDone = { dismissAllowingStateLoss() }) }
        }
}

private val Green get() = DriftAccent.compose
private val Sub = Color(0xFFB3B3B3)
private val RowBg = Color(0xFF2E2E34)

@Composable
private fun DevicesContent(onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val router = remember { MediaRouter.getInstance(ctx.applicationContext) }
    val sel = remember { DriftDevices.selector(ctx) }
    var tick by remember { mutableIntStateOf(0) }

    // Active discovery only while the sheet is open; every change redraws the list
    DisposableEffect(Unit) {
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) { tick++ }
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) { tick++ }
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) { tick++ }
            override fun onRouteVolumeChanged(r: MediaRouter, route: MediaRouter.RouteInfo) { tick++ }
            override fun onRouteSelected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) { tick++ }
            override fun onRouteUnselected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) { tick++ }
        }
        router.addCallback(sel, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        // Headphones or car connecting/disconnecting while the sheet is open
        val audio = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { tick++ }
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { tick++ }
        }
        audio.registerAudioDeviceCallback(devices, null)
        onDispose { router.removeCallback(cb); audio.unregisterAudioDeviceCallback(devices) }
    }

    if (tick < 0) return  // reads tick: every router event redraws
    // Recomputed on every open and every change: no "ghost" Bluetooth devices that are no longer connected
    val bt = DriftDevices.connectedBluetooth(ctx)
    val selected = router.selectedRoute
    val current = if (DriftDevices.isPresent(selected, bt)) selected else router.defaultRoute
    val routes = router.routes.filter {
        it.isEnabled && it.matchesSelector(sel) && !it.isDefault && it != current && DriftDevices.isPresent(it, bt)
    }
        .sortedWith(compareBy({ it.isBluetooth }, { DriftDevices.name(ctx, it).lowercase() }))

    Column(
        Modifier.fillMaxWidth().background(Color(0xFF242429)).navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(Color(0xFF5A5A60)))
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.drift_devices_current), color = Sub, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        CurrentDevice(current, router, onDisconnect = { router.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED); onDone() })

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.drift_devices_select), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            CircularProgressIndicator(Modifier.size(16.dp), color = Sub, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(8.dp))

        if (DriftDevices.isRemote(current) || current.isBluetooth) {
            DeviceRow(router.defaultRoute, stringResource(R.string.drift_devices_this_phone), connecting = false) { router.defaultRoute.select(); onDone() }
        }
        routes.forEach { r ->
            val connecting = r.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING
            DeviceRow(r, DriftDevices.name(ctx, r), connecting) { r.select(); if (!DriftDevices.isRemote(r)) onDone() }
        }
        if (routes.isEmpty()) {
            Text(stringResource(R.string.drift_devices_searching),
                color = Sub, fontSize = 14.sp, modifier = Modifier.padding(vertical = 14.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CurrentDevice(r: MediaRouter.RouteInfo, router: MediaRouter, onDisconnect: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RowBg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(DriftDevices.icon(r)), null, tint = Green, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(DriftDevices.name(ctx, r), color = Green, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DriftDevices.kind(ctx, r), color = Sub, fontSize = 13.sp)
            }
            if (DriftDevices.isRemote(r)) {
                Text(stringResource(R.string.drift_devices_disconnect), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(Color(0xFF3E3E44)).clickable { onDisconnect() }
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        if (DriftDevices.isRemote(r) && r.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE && r.volumeMax > 0) {
            var v by remember(r.id) { mutableFloatStateOf(r.volume.toFloat()) }
            Spacer(Modifier.height(6.dp))
            Slider(
                value = v, valueRange = 0f..r.volumeMax.toFloat(),
                onValueChange = { v = it; r.requestSetVolume(it.toInt()) },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Green, inactiveTrackColor = Color(0xFF4D4D4D)),
            )
        }
    }
}

@Composable
private fun DeviceRow(r: MediaRouter.RouteInfo, name: String, connecting: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !connecting) { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(painterResource(DriftDevices.icon(r)), null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (connecting) stringResource(R.string.drift_devices_connecting) else DriftDevices.kind(LocalContext.current, r), color = Sub, fontSize = 13.sp)
        }
        if (connecting) CircularProgressIndicator(Modifier.size(18.dp), color = Green, strokeWidth = 2.dp)
    }
}
