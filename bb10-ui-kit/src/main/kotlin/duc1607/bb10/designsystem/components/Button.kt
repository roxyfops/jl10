package duc1607.bb10.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.theme.Bb10
import duc1607.bb10.designsystem.tokens.touchHeight

@Composable
fun Bb10Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    primary: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    destructive: Boolean = false,
) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val highlight = rememberBb10Highlight()
    Box(
        modifier
            .defaultMinSize(minWidth = 88.dp)
            .padding(horizontal = d.spaceL, vertical = d.spaceXs),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = d.touchHeight)
                .bb10Clickable(
                    enabled = enabled,
                    role = Role.Button,
                    focusRequester = focusRequester,
                    highlight = highlight,
                    paintHighlight = false,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(DropdownBarHeight)
                    .then(
                        when {
                            destructive -> Modifier.background(DestructiveBrush)
                            primary -> Modifier.background(c.accentBrush)
                            else -> Modifier.background(c.field)
                        }
                    )
                    .bb10HighlightFill(highlight)
                    .padding(horizontal = d.spaceM),
                contentAlignment = Alignment.Center,
            ) {
                Bb10OnHighlight(highlight) {
                    Bb10Text(
                        text = text,
                        style = Bb10.typography.button.copy(fontWeight = FontWeight.Bold),
                        color = if (destructive) androidx.compose.ui.graphics.Color.White else if (primary) c.onAccent else c.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val DestructiveBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(androidx.compose.ui.graphics.Color(0xFFD8352F), androidx.compose.ui.graphics.Color(0xFFB0201B)),
)
