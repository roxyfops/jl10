package duc1607.bb10.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.pointer.pointerInput
import duc1607.bb10.designsystem.theme.Bb10

internal val LocalBb10InPage = staticCompositionLocalOf { false }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T : Any> Bb10PushHost(
    pushed: T?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    page: @Composable (T) -> Unit,
) {
    val c = Bb10.colors
    val showing = pushed != null
    var last by remember { mutableStateOf<T?>(null) }
    if (pushed != null) last = pushed

    val dim by animateFloatAsState(
        targetValue = if (showing) 1f else 0f,
        animationSpec = tween(440),
        label = "pushDim",
    )
    val baseFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    var wasShowing by remember { mutableStateOf(false) }

    LaunchedEffect(showing) {
        withFrameNanos { }
        if (showing) {
            runCatching { pageFocus.requestFocus() }
        } else if (wasShowing) {
            runCatching { baseFocus.requestFocus() }
        }
        wasShowing = showing
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(baseFocus)
                .focusRestorer()
                .focusGroup(),
        ) { content() }

        if (dim > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.scrim.copy(alpha = c.scrim.alpha * dim))
                    .pointerInput(Unit) { detectTapGestures { } }
            )
        }

        AnimatedVisibility(
            visible = showing,
            enter = slideInHorizontally(tween(480)) { it },
            exit = slideOutHorizontally(tween(400)) { it },
        ) {
            val shown = last
            if (shown != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(pageFocus)
                        .focusProperties { exit = { FocusRequester.Cancel } }
                        .focusGroup(),
                ) {
                    CompositionLocalProvider(LocalBb10InPage provides true) { page(shown) }
                }
            }
        }
    }
}
