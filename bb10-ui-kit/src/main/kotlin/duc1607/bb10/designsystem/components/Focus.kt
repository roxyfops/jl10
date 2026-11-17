package duc1607.bb10.designsystem.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import duc1607.bb10.designsystem.theme.Bb10
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
class Bb10Highlight internal constructor(
    internal val source: MutableInteractionSource,
    val focused: Boolean,
    val pressed: Boolean,
) {
    val active: Boolean get() = focused || pressed
}

@Composable
fun rememberBb10Highlight(): Bb10Highlight {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    return remember(source, focused, pressed) { Bb10Highlight(source, focused, pressed) }
}

@Composable
fun Bb10OnHighlight(highlight: Bb10Highlight, content: @Composable () -> Unit) {
    val forced = if (highlight.active) Bb10.colors.onAccent else LocalBb10ContentColor.current
    CompositionLocalProvider(LocalBb10ContentColor provides forced, content = content)
}

@Composable
fun Modifier.bb10HighlightFill(
    highlight: Bb10Highlight,
    circle: Boolean = false,
    slant: Dp = 0.dp,
    flat: Boolean = false,
): Modifier {
    val colors = Bb10.colors
    val fill: Brush? = when {
        highlight.pressed -> SolidColor(colors.pressedFill)
        highlight.focused -> if (flat) SolidColor(colors.focusFill) else colors.selectionBrush
        else -> null
    }
    if (fill == null) return this
    return drawWithContent {
        when {
            circle -> drawCircle(fill, radius = minOf(size.width, size.height) / 2)
            slant > 0.dp -> {
                val lean = slant.toPx()
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width - lean, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, fill)
            }
            else -> drawRect(fill)
        }
        drawContent()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun InputModeManager.focusForKeys(requester: FocusRequester) {
    requestInputMode(InputMode.Keyboard)
    runCatching { requester.requestFocus() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bb10Clickable(
    enabled: Boolean = true,
    role: Role? = null,
    focusRequester: FocusRequester? = null,
    highlight: Bb10Highlight = rememberBb10Highlight(),
    paintHighlight: Boolean = true,
    flat: Boolean = false,
    circle: Boolean = false,

    slant: Dp = 0.dp,
    onLongClick: (() -> Unit)? = null,
    onHold: (() -> Unit)? = null,
    onHoldRelease: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = highlight.source
    val currentClick by rememberUpdatedState(onClick)
    val currentLongClick by rememberUpdatedState(onLongClick)
    val currentHold by rememberUpdatedState(onHold)
    val currentHoldRelease by rememberUpdatedState(onHoldRelease)
    val holdEnabled = enabled && onHold != null

    return this
        .alpha(if (enabled) 1f else 0.4f)
        .onKeyEvent { e ->
            if (!enabled || e.type != KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount != 0) {
                return@onKeyEvent false
            }
            when (e.key) {
                Key.Spacebar -> { currentClick(); true }
                Key.Menu -> currentLongClick?.let { it(); true } ?: false
                else -> false
            }
        }
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .then(if (paintHighlight) Modifier.bb10HighlightFill(highlight, circle, slant, flat) else Modifier)
        .pointerInput(holdEnabled) {
            if (!holdEnabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var done = false
                    while (!done) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        done = change == null || !change.pressed ||
                            (change.position - down.position).getDistance() > slop
                    }
                    true
                }
                if (ended != null) return@awaitEachGesture

                currentHold?.invoke()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    event.changes.forEach { it.consume() }
                    if (change == null || !change.pressed) break
                }
                currentHoldRelease?.invoke()
            }
        }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = if (onHold != null) null else onLongClick,
            onClick = onClick,
        )
}

fun Key.hintLabel(): String? {
    if (this == Key.Delete || this == Key.Backspace) return "Del"
    val code = nativeKeyCode
    return when (code) {
        in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z ->
            ('A' + (code - AndroidKeyEvent.KEYCODE_A)).toString()
        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ->
            ('0' + (code - AndroidKeyEvent.KEYCODE_0)).toString()
        else -> null
    }
}
