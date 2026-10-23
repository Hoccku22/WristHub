package dev.wristreader.wear

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs

@Composable fun Modifier.rotary(onScroll: (Float) -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    val callback by rememberUpdatedState(onScroll)
    LaunchedEffect(Unit) { focus.requestFocus() }
    return onRotaryScrollEvent { callback(it.verticalScrollPixels); true }.focusRequester(focus).focusable()
}

/** При масштабе 1 одиночный свайп перелистывает, при увеличении — перемещает. */
@Composable fun Modifier.zoomable(
    resetKey: Any, locked: Boolean, vertical: Boolean,
    onSwipe: (Int) -> Unit = {}, initialScale: Float = 1f
): Modifier {
    var zoom by remember(resetKey, initialScale) { mutableFloatStateOf(initialScale) }
    var pan by remember(resetKey, initialScale) { mutableStateOf(Offset.Zero) }
    val callback by rememberUpdatedState(onSwipe)
    return pointerInput(resetKey, locked, vertical, initialScale) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var total = Offset.Zero
            var multiple = false
            do {
                val event = awaitPointerEvent()
                val delta = event.calculatePan()
                if (!locked) {
                    if (event.changes.count { it.pressed } > 1) multiple = true
                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                    if (zoom > 1.02f) {
                        val maxX = size.width * (zoom - 1f) / 2f
                        val maxY = size.height * (zoom - 1f) / 2f
                        pan = Offset((pan.x + delta.x).coerceIn(-maxX, maxX), (pan.y + delta.y).coerceIn(-maxY, maxY))
                    } else { zoom = 1f; pan = Offset.Zero; total += delta }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            val movement = if (vertical) total.y else total.x
            val cross = if (vertical) total.x else total.y
            if (!locked && !multiple && zoom == 1f && abs(movement) > 36 * density && abs(movement) > abs(cross))
                callback(if (movement < 0) 1 else -1)
        }
    }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
}

class RotaryPages {
    private var sum = 0f
    fun move(delta: Float, sensitivity: Float, step: (Int) -> Unit) {
        sum += delta * sensitivity
        if (abs(sum) >= 40f) { step(if (sum > 0) 1 else -1); sum = 0f }
    }
}
