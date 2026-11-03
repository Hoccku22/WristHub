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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    onSwipe: (Int) -> Unit = {}, initialScale: Float = 1f, onTap: () -> Unit = {}, contentAspect: Float? = null,
    onPageTap: (Offset) -> Unit = {}, scrollAtBase: Boolean = false
): Modifier {
    var zoom by remember(resetKey, initialScale) { mutableFloatStateOf(initialScale) }
    var pan by remember(resetKey, initialScale) { mutableStateOf(Offset.Zero) }
    val callback by rememberUpdatedState(onSwipe)
    val tap by rememberUpdatedState(onTap)
    val pageTap by rememberUpdatedState(onPageTap)
    val scope = rememberCoroutineScope()
    var pendingTap by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(resetKey, locked, initialScale) { onDispose { pendingTap?.cancel(); pendingTap = null } }
    return semantics { stateDescription = "zoom:$zoom" }
        .pointerInput(resetKey, locked, vertical, initialScale, contentAspect, scrollAtBase) {
        var lastTapTime = 0L
        var lastTapPosition = Offset.Zero
        fun contentSize(): Offset {
            val aspect = contentAspect ?: (size.width.toFloat() / size.height)
            return Offset(minOf(size.width.toFloat(),size.height * aspect), minOf(size.height.toFloat(),size.width / aspect))
        }
        fun clampPan(value: Offset): Offset {
            val content = contentSize()
            val maxX = ((content.x * zoom - size.width) / 2f).coerceAtLeast(0f)
            val maxY = ((content.y * zoom - size.height) / 2f).coerceAtLeast(0f)
            return Offset(value.x.coerceIn(-maxX,maxX), value.y.coerceIn(-maxY,maxY))
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var total = Offset.Zero
            var multiple = false
            var travel = 0f
            var cancelled = false
            var up = down
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) cancelled = true
                up = event.changes.first()
                val delta = event.calculatePan()
                travel += delta.getDistance()
                if (event.changes.count { it.pressed } > 1) multiple = true
                if (!locked) {
                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, maxOf(6f, initialScale))
                    if (zoom > 1.02f) {
                        pan = clampPan(pan + delta)
                    } else { zoom = 1f; pan = Offset.Zero; total += delta }
                    if (!scrollAtBase || zoom > 1.02f || multiple)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (!cancelled && !multiple && travel <= viewConfiguration.touchSlop &&
                up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                val interval = down.uptimeMillis - lastTapTime
                if (!locked && pendingTap?.isActive == true &&
                    interval in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                    (down.position - lastTapPosition).getDistance() <= 48 * density) {
                    pendingTap?.cancel()
                    val oldZoom = zoom
                    zoom = if (zoom > initialScale + 0.05f) initialScale else minOf(maxOf(6f,initialScale),initialScale * 2)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    pan = if (zoom == initialScale) Offset.Zero else
                        clampPan((pan - (down.position - center)) * (zoom / oldZoom) + (down.position - center))
                    lastTapTime = 0L
                } else {
                    pendingTap?.cancel()
                    lastTapTime = up.uptimeMillis
                    lastTapPosition = down.position
                    val content = contentSize()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val local = (up.position - center - pan) / zoom
                    val point = Offset(local.x / content.x + 0.5f, local.y / content.y + 0.5f)
                    pendingTap = scope.launch {
                        delay(viewConfiguration.doubleTapTimeoutMillis)
                        if (!locked && point.x in 0f..1f && point.y in 0f..1f) pageTap(point)
                        tap()
                    }
                }
            } else { pendingTap?.cancel(); lastTapTime = 0L }
            val movement = if (vertical) total.y else total.x
            val cross = if (vertical) total.x else total.y
            if (!scrollAtBase && !cancelled && !locked && !multiple && zoom == 1f && abs(movement) > 36 * density && abs(movement) > abs(cross))
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
