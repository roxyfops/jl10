package duc1607.bb10.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.icons.Bb10Icons
import duc1607.bb10.designsystem.theme.Bb10

@Composable
fun Bb10Checkbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val t = Bb10.typography
    val highlight = rememberBb10Highlight()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = d.listItemMinHeight)
                .bb10Clickable(
                    enabled = enabled,
                    role = Role.Checkbox,
                    focusRequester = focusRequester,
                    highlight = highlight,
                    flat = true,
                    onClick = { onCheckedChange(!checked) },
                )
                .semantics { toggleableState = ToggleableState(checked) }
                .padding(horizontal = d.spaceL, vertical = d.spaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceM),
        ) {
            Bb10OnHighlight(highlight) {
                if (icon != null) Bb10Icon(icon, tint = if (checked) c.accent else c.textPrimary)
                Column(Modifier.weight(1f)) {
                    Bb10Text(label, style = t.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Bb10Text(subtitle, style = t.secondary, color = c.textSecondary)
                    }
                }
                CheckboxBox(checked)
            }
        }
        Bb10Divider()
    }
}

@Composable
fun Bb10CheckboxBox(checked: Boolean, modifier: Modifier = Modifier) = CheckboxBox(checked, modifier)

@Composable
private fun CheckboxBox(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Bb10.colors
    val unchecked = if (c.isDark) Color(0xFF4A4A4A) else Color(0xFFD0D0D0)
    val onHighlight = LocalBb10ContentColor.current != Color.Unspecified
    Box(
        modifier
            .size(26.dp)
            .background(
                brush = when {
                    onHighlight -> SolidColor(c.onAccent.copy(alpha = 0.25f))
                    checked -> c.accentBrush
                    else -> SolidColor(unchecked)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Bb10Icon(Bb10Icons.Check, tint = c.onAccent, size = 20.dp)
    }
}
