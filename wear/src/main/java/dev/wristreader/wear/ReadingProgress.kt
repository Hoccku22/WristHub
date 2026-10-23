package dev.wristreader.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

@Stable
class ReadingProgressState {
    var fraction by mutableFloatStateOf(0f)
        private set
    fun update(value: Float) { fraction = if (value.isFinite()) value.coerceIn(0f,1f) else 0f }
    fun page(index: Int, count: Int) { update(if (count > 0) (index + 1f) / count else 0f) }
}

/** Прокрутка обновляет только рисование кольца, без переразметки всего документа. */
@Composable fun TrackScrollProgress(state: LazyListState, progress: ReadingProgressState) {
    LaunchedEffect(state,progress) {
        snapshotFlow {
            val layout = state.layoutInfo
            val count = layout.totalItemsCount
            val first = layout.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
            when {
                count == 0 || first == null -> 0f
                !state.canScrollForward -> 1f
                else -> (state.firstVisibleItemIndex + 1f +
                    state.firstVisibleItemScrollOffset.toFloat() / first.size.coerceAtLeast(1)) / count
            }
        }.distinctUntilChanged().collect { progress.update(it) }
    }
}

@Composable fun ReadingProgressRing(progress: ReadingProgressState) {
    Canvas(Modifier.fillMaxSize().testTag("reading-progress").semantics {
        contentDescription = "Прогресс чтения"
        progressBarRangeInfo = ProgressBarRangeInfo(progress.fraction,0f..1f)
    }) {
        val stroke = 2.dp.toPx()
        val diameter = size.minDimension - 4.dp.toPx()
        val origin = Offset((size.width - diameter) / 2,(size.height - diameter) / 2)
        val bounds = Size(diameter,diameter)
        val blue = Color(0xFF42A5F5)
        drawArc(blue.copy(alpha = 0.18f),-90f,360f,false,origin,bounds,style = Stroke(stroke))
        if (progress.fraction > 0f) drawArc(blue,-90f,360f * progress.fraction,false,origin,bounds,
            style = Stroke(stroke,cap = StrokeCap.Round))
    }
}
