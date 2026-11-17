package duc1607.bb10.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.icons.Bb10Icons
import duc1607.bb10.designsystem.theme.Bb10

@Immutable
class Bb10Action(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val shortcut: Key? = null,
    val enabled: Boolean = true,
    val primary: Boolean = false,
    val onClick: () -> Unit,
)

internal fun shortcutMatches(shortcut: Key?, pressed: Key): Boolean {
    if (shortcut == null) return false
    if (shortcut == pressed) return true
    fun Key.isDelete() = this == Key.Delete || this == Key.Backspace
    return shortcut.isDelete() && pressed.isDelete()
}

internal class ActionSlots(
    val primary: Bb10Action?,
    val visible: List<Bb10Action>,
    val overflow: List<Bb10Action>,
)

internal fun splitActions(actions: List<Bb10Action>, maxVisible: Int): ActionSlots {
    val primary = actions.firstOrNull { it.primary }
    val others = actions.filter { it !== primary }
    val capacity = (maxVisible - if (primary != null) 1 else 0).coerceAtLeast(0)
    return if (others.size <= capacity) {
        ActionSlots(primary, others, emptyList())
    } else {
        val keep = (capacity - 1).coerceAtLeast(0)
        ActionSlots(primary, others.take(keep), others.drop(keep))
    }
}

@Composable
fun Bb10StandaloneActionBar(
    actions: List<Bb10Action>,
    modifier: Modifier = Modifier,
    leading: Bb10Action? = null,
    maxVisibleActions: Int = 5,
    onMore: () -> Unit = {},

    collapse: Float = 0f,
    metrics: Bb10BarMetrics? = null,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalBarMetrics provides metrics) {
        Bb10ActionBar(splitActions(actions, maxVisibleActions), modifier, leading = leading, onMore = onMore, collapse = collapse)
    }
}

class Bb10BarMetrics(
    val height: Dp,
    val primarySize: Dp,
    val primarySlot: Dp,
    val sideWidth: Dp,
    val itemWidth: Dp,
    val iconSize: Dp,
    val labelPad: Dp,
    val itemGap: Dp,
    val primaryGap: Dp,
    val labelSize: androidx.compose.ui.unit.TextUnit,
    val foldDrop: Dp,
)

internal val LocalBarMetrics = androidx.compose.runtime.staticCompositionLocalOf<Bb10BarMetrics?> { null }

@Composable
fun bb10BarMetrics(scale: Float = 0.85f): Bb10BarMetrics {
    val w = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.widthPixels
    val d = androidx.compose.ui.platform.LocalDensity.current
    fun px(p: Float): Dp = with(d) { (p * scale * w / 720f).toDp() }
    val label = androidx.compose.ui.unit.TextUnit(15.5f * scale * 1.12f * w / 720f / (d.density * d.fontScale), androidx.compose.ui.unit.TextUnitType.Sp)
    return Bb10BarMetrics(
        height = px(109f), primarySize = px(108f), primarySlot = px(180f), sideWidth = px(106f),
        itemWidth = px(130f), iconSize = px(56f), labelPad = px(7f), itemGap = px(13f), primaryGap = px(11f),
        labelSize = label, foldDrop = px(30f),
    )
}

private val MoreButtonWidth = 52.dp

private val BackButtonWidth = 56.dp

private val BackSlashLean = 10.dp

private val ActionIconSize = 28.dp
private val IconLabelGap = 4.dp

@Composable
private fun labelBottomPadding(): Dp {
    val d = Bb10.dimens
    return ((d.actionBarHeight - ActionIconSize - IconLabelGap - 14.dp) / 2).coerceAtLeast(4.dp)
}

@Composable
internal fun Bb10ActionBar(
    slots: ActionSlots,
    modifier: Modifier = Modifier,
    leading: Bb10Action? = null,
    leadingIsBack: Boolean = false,
    leadingShowsLabel: Boolean = false,
    moreFocusRequester: FocusRequester? = null,
    onMore: () -> Unit = {},
    collapse: Float = 0f,
) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val primary = slots.primary
    val hasOverflow = slots.overflow.isNotEmpty()
    val m = LocalBarMetrics.current
    val barHeight = m?.height ?: d.actionBarHeight
    val moreWidth = m?.sideWidth ?: MoreButtonWidth
    val strings = Bb10.strings
    val more = remember(onMore, strings) {
        Bb10Action("more", strings.moreActions, Bb10Icons.More, onClick = onMore)
    }
    val sides = hasOverflow || leading != null
    val sideWidth = m?.sideWidth ?: if (leadingIsBack) BackButtonWidth else MoreButtonWidth

    if (primary == null && slots.visible.isEmpty()) {
        val tile = c.surfaceVariant.copy(alpha = 0.9f)
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (leading != null) {
                ActionBarItem(leading, Modifier.size(barHeight).background(tile), showLabel = leadingShowsLabel)
            }
            Spacer(Modifier.weight(1f))
            if (hasOverflow) {
                ActionBarItem(
                    more,
                    Modifier.size(barHeight).background(tile),
                    focusRequester = moreFocusRequester,
                    showLabel = false,
                )
            }
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val count = slots.visible.size
        val primarySlot = if (primary != null) m?.primarySlot ?: (d.primaryActionSize + 32.dp) else 0.dp
        val reserved = (if (sides) sideWidth * 2 else 0.dp) + primarySlot
        val leftCount = if (primary != null) count / 2 else 0
        val rightCount = count - leftCount
        val itemWidth = when {
            count == 0 -> 0.dp
            m != null -> m.itemWidth
            primary == null -> minOf(72.dp, (maxWidth - reserved) / count)
            else -> minOf(72.dp, ((maxWidth - reserved) / 2) / maxOf(leftCount, rightCount))
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer { translationY = collapse * size.height }
                .background(c.surfaceVariant)
        ) {
            Spacer(Modifier.height(barHeight))
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (leading != null) {
                ActionBarItem(
                    leading,
                    modifier = Modifier.width(sideWidth).height(barHeight).background(c.surfaceVariant),
                    showLabel = leadingShowsLabel,
                    slant = if (leadingIsBack) BackSlashLean else 0.dp,
                )
            } else if (sides) {
                Spacer(Modifier.width(sideWidth))
            }
            if (primary == null) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    slots.visible.forEach { a ->
                        ActionBarItem(a, Modifier.width(itemWidth).height(barHeight))
                    }
                }
            } else {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        slots.visible.take(leftCount).forEach { a ->
                            if (collapse < 1f) ActionBarItem(a, Modifier.width(itemWidth).height(barHeight).graphicsLayer { translationY = collapse * size.height })
                            else Spacer(Modifier.width(itemWidth).height(barHeight))
                        }
                    }
                    PrimaryActionItem(primary, Modifier.width(primarySlot), labelAlpha = 1f - collapse)
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        slots.visible.drop(leftCount).forEach { a ->
                            if (collapse < 1f) ActionBarItem(a, Modifier.width(itemWidth).height(barHeight).graphicsLayer { translationY = collapse * size.height })
                            else Spacer(Modifier.width(itemWidth).height(barHeight))
                        }
                    }
                }
            }
            if (hasOverflow) {
                if (sideWidth > moreWidth) Spacer(Modifier.width(sideWidth - moreWidth))
                ActionBarItem(
                    more,
                    modifier = Modifier.width(moreWidth).height(barHeight).background(c.surfaceVariant),
                    focusRequester = moreFocusRequester,
                    showLabel = false,
                )
            } else if (sides) {
                Spacer(Modifier.width(sideWidth))
            }
        }
    }
}

@Composable
private fun ActionBarItem(
    action: Bb10Action,
    modifier: Modifier,
    focusRequester: FocusRequester? = null,
    showLabel: Boolean = true,
    slant: Dp = 0.dp,
) {
    val c = Bb10.colors
    val m = LocalBarMetrics.current
    val highlight = rememberBb10Highlight()
    Box(
        modifier
            .semantics { if (!showLabel) contentDescription = action.label }
            .bb10Clickable(
                enabled = action.enabled,
                role = Role.Button,
                focusRequester = focusRequester,
                highlight = highlight,
                slant = slant,
                onClick = action.onClick,
            )
            .then(
                if (slant > 0.dp) {
                    Modifier.drawBehind {
                        val lean = slant.toPx()
                        drawLine(
                            color = c.accent,
                            start = Offset(size.width, 0f),
                            end = Offset(size.width - lean, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                } else Modifier
            )
    ) {
        Bb10OnHighlight(highlight) {
            Column(
                if (showLabel) {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = m?.labelPad ?: labelBottomPadding())
                } else {
                    Modifier.align(Alignment.Center).padding(end = slant)
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Bb10Icon(action.icon, size = m?.iconSize ?: ActionIconSize)
                if (showLabel) {
                    Spacer(Modifier.height(m?.itemGap ?: IconLabelGap))
                    Bb10Text(
                        action.label,
                        style = if (m != null) Bb10.typography.action.copy(fontSize = m.labelSize) else Bb10.typography.action,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryActionItem(
    action: Bb10Action,
    modifier: Modifier,
    labelAlpha: Float = 1f,
) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val m = LocalBarMetrics.current
    val lbp = m?.labelPad ?: labelBottomPadding()
    val drop = with(androidx.compose.ui.platform.LocalDensity.current) {
        (m?.foldDrop ?: (lbp + IconLabelGap + 6.dp + d.primaryActionSize / 2 - d.actionBarHeight / 2)).toPx()
    }
    Column(
        modifier.padding(bottom = lbp).graphicsLayer { translationY = (1f - labelAlpha) * drop },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(m?.primarySize ?: d.primaryActionSize)
                .clip(CircleShape)
                .background(c.accentBrush)
                .semantics { contentDescription = action.label }
                .bb10Clickable(
                    enabled = action.enabled,
                    role = Role.Button,
                    circle = true,
                    onClick = action.onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Bb10Icon(action.icon, tint = c.onAccent, size = m?.iconSize ?: ActionIconSize)
        }
        Spacer(Modifier.height(m?.primaryGap ?: IconLabelGap))
        Bb10Text(
            action.label,
            style = if (m != null) Bb10.typography.action.copy(fontSize = m.labelSize) else Bb10.typography.action,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(if (labelAlpha < 0.5f) 0f else 1f),
        )
    }
}

@Composable
internal fun Bb10OverflowMenu(
    state: PanelState,
    actions: List<Bb10Action>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showHints: Boolean = false,
) {
    val first = remember { FocusRequester() }
    Bb10SidePanel(
        title = Bb10.strings.moreActions,
        state = state,
        side = PanelSide.End,
        onDismiss = onDismiss,
        initialFocus = first,
        modifier = modifier,
        bottomAligned = true,
    ) {
        actions.forEachIndexed { index, a ->
            Bb10LinkItem(
                title = a.label,
                icon = a.icon,
                hint = if (showHints) a.shortcut?.hintLabel() else null,
                enabled = a.enabled,
                focusRequester = if (index == 0) first else null,
                onClick = {
                    onDismiss()
                    a.onClick()
                },
            )
        }
    }
}

@androidx.compose.runtime.Stable
class Bb10ContextMenuState internal constructor() {
    internal var state by androidx.compose.runtime.mutableStateOf(PanelState.Hidden)
    val visible: Boolean get() = state != PanelState.Hidden
    fun peek() {
        if (state == PanelState.Hidden) state = PanelState.Peek
    }
    fun open() {
        state = PanelState.Open
    }
    fun close() {
        state = PanelState.Hidden
    }
}

@Composable
fun rememberBb10ContextMenu(): Bb10ContextMenuState = remember { Bb10ContextMenuState() }

@Composable
fun Bb10ContextMenu(menu: Bb10ContextMenuState, title: String?, actions: List<Bb10Action>) {
    androidx.activity.compose.BackHandler(menu.visible) { menu.close() }
    val first = remember { FocusRequester() }
    val showHints = androidx.compose.ui.platform.LocalConfiguration.current.keyboard !=
        android.content.res.Configuration.KEYBOARD_NOKEYS
    Bb10SidePanel(
        title = title ?: Bb10.strings.moreActions,
        state = menu.state,
        side = PanelSide.End,
        onDismiss = { menu.close() },
        initialFocus = first,
        bottomAligned = true,
    ) {
        actions.forEachIndexed { index, a ->
            Bb10LinkItem(
                title = a.label,
                icon = a.icon,
                hint = if (showHints) a.shortcut?.hintLabel() else null,
                enabled = a.enabled,
                focusRequester = if (index == 0) first else null,
                onClick = {
                    menu.close()
                    a.onClick()
                },
            )
        }
    }
}

@Composable
fun Modifier.bb10ContextClickable(
    menu: Bb10ContextMenuState,
    highlight: Bb10Highlight = rememberBb10Highlight(),
    focusRequester: FocusRequester? = null,
    onPick: () -> Unit,
    onClick: () -> Unit,
): Modifier = bb10Clickable(
    highlight = highlight,
    flat = true,
    focusRequester = focusRequester,
    onLongClick = { onPick(); menu.open() },
    onHold = { onPick(); menu.peek() },
    onHoldRelease = { menu.open() },
    onClick = onClick,
)