package duc1607.bb10.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import duc1607.bb10.designsystem.theme.Bb10
import duc1607.bb10.designsystem.tokens.touchHeight

@Composable
fun Bb10Dialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissText: String? = null,
    neutralText: String? = null,
    onNeutral: () -> Unit = {},
    focusContent: Boolean = false,
    confirmEnabled: Boolean = true,

    content: (@Composable (focusConfirm: () -> Unit) -> Unit)? = null,
) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val confirmFocus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            withFrameNanos { }
            if (!focusContent) inputMode.focusForKeys(confirmFocus)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier
                    .fillMaxWidth(0.85f)
                    .widthIn(max = 420.dp)
                    .background(c.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = title }
            ) {
                Column(Modifier.padding(d.spaceL)) {
                    Bb10Text(
                        title,
                        style = Bb10.typography.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (message != null) {
                        Spacer(Modifier.height(d.spaceS))
                        Bb10Text(message, style = Bb10.typography.body, color = c.textSecondary)
                    }
                }
                if (content != null) {
                    content { inputMode.focusForKeys(confirmFocus) }
                }
                val alone = dismissText == null && neutralText == null
                if (!alone) Bb10Divider()
                Row(Modifier.fillMaxWidth().height(d.touchHeight + 4.dp)) {
                    if (dismissText != null) {
                        DialogButton(dismissText, onDismiss, filled = false, Modifier.weight(1f))
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
                    }
                    if (neutralText != null) {
                        DialogButton(neutralText, onNeutral, filled = false, Modifier.weight(1f))
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
                    }
                    DialogButton(
                        confirmText,
                        onConfirm,
                        filled = alone,
                        Modifier.weight(1f),
                        confirmFocus,
                        enabled = confirmEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val c = Bb10.colors
    val highlight = rememberBb10Highlight()
    Box(
        modifier
            .fillMaxHeight()
            .then(
                if (filled) Modifier.background(c.accentBrush) else Modifier.background(c.surfaceVariant)
            )
            .bb10Clickable(
                enabled = enabled,
                role = Role.Button,
                focusRequester = focusRequester,
                highlight = highlight,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Bb10OnHighlight(highlight) {
            Bb10Text(
                text = text,
                style = Bb10.typography.button,
                color = if (filled) c.onAccent else c.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun Bb10InputDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    label: String? = null,
    placeholder: String = "",
    helper: String? = null,
    error: String? = null,
    password: Boolean = false,
    maxLength: Int? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    confirmText: String = Bb10.strings.ok,
    dismissText: String = Bb10.strings.cancel,
    confirmEnabled: Boolean = error == null,
) {
    Bb10Dialog(
        title = title,
        confirmText = confirmText,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier,
        message = message,
        dismissText = dismissText,
        focusContent = true,
        confirmEnabled = confirmEnabled,
    ) { focusConfirm ->
        Bb10TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            helper = helper,
            error = error,
            password = password,
            maxLength = maxLength,
            keyboardOptions = keyboardOptions,
            startEditing = true,
            onDone = focusConfirm,
        )
    }
}
