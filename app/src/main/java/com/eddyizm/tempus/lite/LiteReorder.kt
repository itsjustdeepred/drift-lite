package com.eddyizm.tempus.lite

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Drift Lite: long-press a row and drag it to a new place (playlists, songs in a playlist).
 * [canMove] says which LazyColumn keys take part; [onMove] swaps two of them in the data (called while dragging).
 */
class ReorderState internal constructor(
    private val list: LazyListState,
    private val haptic: HapticFeedback,
    private val canMove: (Any) -> Boolean,
    private val onMove: (from: Any, to: Any) -> Unit,
    private val onDone: () -> Unit,
) {
    var dragging by mutableStateOf<Any?>(null); private set
    var offset by mutableFloatStateOf(0f); private set

    internal fun start(key: Any) { dragging = key; offset = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    internal fun drag(dy: Float) {
        val key = dragging ?: return
        offset += dy
        val items = list.layoutInfo.visibleItemsInfo
        val cur = items.firstOrNull { it.key == key } ?: return
        val center = cur.offset + offset + cur.size / 2f
        val target = items.firstOrNull { it.key != key && canMove(it.key) && center >= it.offset && center <= it.offset + it.size } ?: return
        onMove(key, target.key)
        // the row moved in the list: keep it under the finger
        offset += cur.offset - target.offset
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    internal fun end() { if (dragging != null) onDone(); dragging = null; offset = 0f }
}

@Composable
fun rememberReorderState(list: LazyListState, canMove: (Any) -> Boolean, onMove: (Any, Any) -> Unit, onDone: () -> Unit = {}): ReorderState {
    val haptic = LocalHapticFeedback.current
    return remember(list) { ReorderState(list, haptic, canMove, onMove, onDone) }
}

/** Makes a row draggable after a long press; the dragged row floats above the others. */
fun Modifier.reorderable(state: ReorderState, key: Any): Modifier = this
    .then(if (state.dragging == key) Modifier.zIndex(1f).graphicsLayer { translationY = state.offset; scaleX = 1.02f; scaleY = 1.02f }
        .shadow(10.dp) else Modifier)
    .pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(key) },
            onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
        )
    }
