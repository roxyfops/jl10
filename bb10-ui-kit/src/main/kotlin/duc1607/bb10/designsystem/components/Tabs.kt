package duc1607.bb10.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.theme.Bb10

internal val TabHeight = 40.dp

@Composable
fun Bb10TabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        TabStrip(tabs, selectedIndex, onSelect, TabHeight)
        Bb10Divider()
    }
}

@Composable
internal fun TabStrip(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val c = Bb10.colors
    val t = Bb10.typography
    Row(modifier.fillMaxWidth().height(height)) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val highlight = rememberBb10Highlight()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) c.background else c.surfaceVariant)
                    .semantics { selected = isSelected }
                    .bb10Clickable(role = Role.Tab, highlight = highlight, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Bb10OnHighlight(highlight) {
                    Bb10Text(
                        label,
                        style = t.button.copy(fontWeight = FontWeight.Bold),
                        color = if (isSelected) c.accent else c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val next = index + 1
            if (next < tabs.size && !isSelected && next != selectedIndex) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
            }
        }
    }
}
