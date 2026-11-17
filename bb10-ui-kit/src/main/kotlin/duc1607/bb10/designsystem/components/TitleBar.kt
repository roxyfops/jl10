package duc1607.bb10.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.icons.Bb10Icons
import duc1607.bb10.designsystem.theme.Bb10

@Immutable
class Bb10TitleAction(
    val text: String,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
) {
    companion object
}

@Composable
fun Bb10TitleAction.Companion.back(onClick: () -> Unit): Bb10TitleAction {
    val text = Bb10.strings.back
    return remember(text, onClick) { Bb10TitleAction(text, icon = Bb10Icons.ChevronLeft, onClick = onClick) }
}

@Immutable
class Bb10Search(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val onClose: () -> Unit,
    val placeholder: String? = null,
    val cancelText: String? = null,
)

@Composable
fun Bb10TitleBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dismissAction: Bb10TitleAction? = null,
    acceptAction: Bb10TitleAction? = null,
    search: Bb10Search? = null,
    tabs: List<String>? = null,
    selectedTab: Int = 0,
    onTabSelect: (Int) -> Unit = {},
) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val t = Bb10.typography
    val centered = dismissAction != null || acceptAction != null
    val slot = 96.dp

    Column(modifier.fillMaxWidth().background(c.surfaceVariant)) {
        if (search != null) {
            SearchHeader(search)
        } else if (tabs != null) {
            TabStrip(
                tabs, selectedTab, onTabSelect, TabHeight,
                Modifier.semantics { paneTitle = title },
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(d.titleBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    centered -> Box(
                        Modifier.width(slot).fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (dismissAction != null) TitleActionButton(dismissAction)
                    }
                    else -> Spacer(Modifier.width(d.spaceL))
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    Bb10Text(
                        text = title,
                        style = t.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (subtitle != null) {
                        Bb10Text(
                            subtitle,
                            style = t.secondary,
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (centered) {
                    Box(Modifier.width(slot).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                        if (acceptAction != null) TitleActionButton(acceptAction)
                    }
                } else {
                    Spacer(Modifier.width(d.spaceL))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.accent))
    }
}

@Composable
private fun SearchHeader(search: Bb10Search) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val t = Bb10.typography
    val focus = remember { FocusRequester() }
    val strings = Bb10.strings
    val placeholder = search.placeholder ?: strings.search
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(d.titleBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = d.spaceS, vertical = d.spaceS)
                .fillMaxHeight()
                .background(c.field)
                .padding(horizontal = d.spaceM),
            contentAlignment = Alignment.CenterStart,
        ) {
            var tfv by remember { mutableStateOf(TextFieldValue(search.query, TextRange(search.query.length))) }
            if (tfv.text != search.query) tfv = TextFieldValue(search.query, TextRange(search.query.length))
            BasicTextField(
                value = tfv,
                onValueChange = { v -> tfv = v; if (v.text != search.query) search.onQueryChange(v.text) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = placeholder },
                singleLine = true,
                textStyle = t.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (search.query.isEmpty()) {
                            Bb10Text(
                                placeholder,
                                style = t.body.copy(fontStyle = FontStyle.Italic),
                                color = c.textDisabled,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Box(Modifier.width(1.dp).fillMaxHeight(0.6f).background(c.divider))
        TitleActionButton(Bb10TitleAction(search.cancelText ?: strings.cancel, onClick = search.onClose))
    }
}

@Composable
private fun TitleActionButton(action: Bb10TitleAction) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val highlight = rememberBb10Highlight()
    Row(
        Modifier
            .fillMaxHeight()
            .bb10Clickable(
                enabled = action.enabled,
                role = Role.Button,
                highlight = highlight,
                onClick = action.onClick,
            )
            .padding(horizontal = d.spaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Bb10OnHighlight(highlight) {
            if (action.icon != null) Bb10Icon(action.icon, tint = c.accent, size = d.iconSize)
            Bb10Text(action.text, style = Bb10.typography.button, color = c.accent, maxLines = 1)
        }
    }
}
