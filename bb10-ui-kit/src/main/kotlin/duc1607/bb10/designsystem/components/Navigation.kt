package duc1607.bb10.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.theme.Bb10

@Immutable
class Bb10NavItem(
    val title: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    val badge: Int? = null,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Bb10NavDrawer(
    open: Boolean,
    items: List<Bb10NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    val c = Bb10.colors
    val selectedFocus = remember { FocusRequester() }
    val menuTitle = Bb10.strings.navigationMenu
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(open) {
        if (open) {
            withFrameNanos { }
            inputMode.focusForKeys(selectedFocus)
        }
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(width)
            .background(androidx.compose.ui.graphics.Color.Black)
            .verticalScroll(rememberScrollState())
            .semantics { paneTitle = menuTitle }
            .pointerInput(Unit) {
                val threshold = 48.dp.toPx()
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (-drag > threshold) onDismiss() },
                    onHorizontalDrag = { _, dx -> drag += dx },
                )
            }
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
    ) {
        Spacer(Modifier.height(82.dp))
        Bb10Divider(color = androidx.compose.ui.graphics.Color(0xFF161616))
        items.forEachIndexed { index, item ->
            NavRow(
                item = item,
                selected = index == selectedIndex,
                focusRequester = if (index == selectedIndex) selectedFocus else null,
                onClick = {
                    onDismiss()
                    onSelect(index)
                },
            )
        }
    }
}

@Composable
private fun NavRow(item: Bb10NavItem, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val c = Bb10.colors
    val t = Bb10.typography
    val highlight = rememberBb10Highlight()
    Column(Modifier.fillMaxWidth()) {
        Bb10OnHighlight(highlight) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(51.dp)
                    .background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .bb10Clickable(focusRequester = focusRequester, highlight = highlight, flat = true, onClick = onClick)
                    .padding(start = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Bb10Icon(item.icon, size = 27.dp, tint = if (selected) c.onAccent else androidx.compose.ui.graphics.Color(0xFFD0D0D0))
                Spacer(Modifier.width(15.dp))
                Bb10Text(item.title, style = t.body.copy(fontSize = 18.5.sp, lineHeight = 22.sp),
                    color = if (selected) c.onAccent else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Bb10Divider(color = androidx.compose.ui.graphics.Color(0xFF161616))
    }
}
