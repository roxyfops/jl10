package duc1607.bb10.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.theme.Bb10
import duc1607.bb10.designsystem.tokens.touchHeight
import kotlin.math.roundToInt

private val ThumbSize = 24.dp

@Composable
fun Bb10Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    label: String? = null,
    startIcon: ImageVector? = null,
    endIcon: ImageVector? = null,
    showTicks: Boolean = false,
) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val thumbPx = with(LocalDensity.current) { ThumbSize.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onValueChange)
    val span = valueRange.endInclusive - valueRange.start
    val target = if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = if (steps > 0) tween(90) else snap(),
        label = "sliderThumb",
    )
    val keyStep = if (steps > 0) span / (steps + 1) else span * 0.05f
    val thumbColor = if (c.isDark) Color(0xFF8A8A8A) else Color(0xFFB0B0B0)

    fun valueAt(position: Float): Float {
        val f = position.coerceIn(0f, 1f)
        val snapped = if (steps > 0) (f * (steps + 1)).roundToInt() / (steps + 1f) else f
        return valueRange.start + snapped * span
    }

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = d.spaceL, vertical = d.spaceS)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (startIcon != null) {
            Bb10Icon(startIcon)
            Spacer(Modifier.width(d.spaceM))
        }
        Box(
            Modifier
                .weight(1f)
                .height(d.touchHeight)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .semantics {
                    if (label != null) contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = value.coerceIn(valueRange),
                        range = valueRange,
                        steps = steps,
                    )
                    if (enabled) {
                        setProgress { target ->
                            currentChange(valueAt((target - valueRange.start) / span))
                            true
                        }
                    }
                }
                .onKeyEvent { e ->
                    if (!enabled || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> {
                            currentChange((currentValue - keyStep).coerceIn(valueRange))
                            true
                        }
                        Key.DirectionRight -> {
                            currentChange((currentValue + keyStep).coerceIn(valueRange))
                            true
                        }
                        else -> false
                    }
                }
                .focusable(enabled, source)
                .pointerInput(enabled, widthPx, steps, valueRange) {
                    if (!enabled) return@pointerInput
                    val usable = (widthPx - thumbPx).coerceAtLeast(1f)
                    detectTapGestures { p -> currentChange(valueAt((p.x - thumbPx / 2) / usable)) }
                }
                .pointerInput(enabled, widthPx, steps, valueRange) {
                    if (!enabled) return@pointerInput
                    val usable = (widthPx - thumbPx).coerceAtLeast(1f)
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        currentChange(valueAt((change.position.x - thumbPx / 2) / usable))
                    }
                }
                .drawBehind {
                    val cy = size.height / 2
                    drawLine(
                        color = c.accent,
                        start = Offset(thumbPx / 2, cy),
                        end = Offset(size.width - thumbPx / 2, cy),
                        strokeWidth = 2.dp.toPx(),
                    )
                    if (showTicks && steps > 0) {
                        val tick = 8.dp.toPx()
                        for (i in 0..steps + 1) {
                            val x = thumbPx / 2 + i / (steps + 1f) * (size.width - thumbPx)
                            drawLine(
                                color = c.accent,
                                start = Offset(x, cy - tick / 2),
                                end = Offset(x, cy + tick / 2),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                    }
                    val cx = thumbPx / 2 + fraction * (size.width - thumbPx)
                    if (focused) {
                        drawCircle(
                            color = c.accent.copy(alpha = 0.45f),
                            radius = thumbPx / 2 + 6.dp.toPx(),
                            center = Offset(cx, cy),
                        )
                    }
                    drawCircle(thumbColor, radius = thumbPx / 2, center = Offset(cx, cy))
                }
        )
        if (endIcon != null) {
            Spacer(Modifier.width(d.spaceM))
            Bb10Icon(endIcon)
        }
    }
}

@Composable
fun Bb10Stepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    startIcon: ImageVector? = null,
    endIcon: ImageVector? = null,
) {
    require(range.last > range.first) { "A stepper needs at least two values" }
    Bb10Slider(
        value = value.coerceIn(range).toFloat(),
        onValueChange = { onValueChange(it.roundToInt().coerceIn(range)) },
        modifier = modifier,
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        enabled = enabled,
        label = label,
        startIcon = startIcon,
        endIcon = endIcon,
        showTicks = true,
    )
}
