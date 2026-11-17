package duc1607.bb10.designsystem.components

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.icons.Bb10Icons
import duc1607.bb10.designsystem.theme.Bb10
import kotlinx.coroutines.delay

@Composable
fun Bb10Scaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dismissAction: Bb10TitleAction? = null,
    acceptAction: Bb10TitleAction? = null,
    search: Bb10Search? = null,
    actions: List<Bb10Action> = emptyList(),
    maxVisibleActions: Int = 5,
    navigationItems: List<Bb10NavItem> = emptyList(),
    selectedNavigation: Int = 0,
    onNavigate: (Int) -> Unit = {},
    onBack: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    onCancelSelection: () -> Unit = {},
    tabs: List<String>? = null,
    selectedTab: Int = 0,
    onTabSelect: (Int) -> Unit = {},
    autoFocus: Boolean = true,

    showTitle: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Bb10.colors
    val d = Bb10.dimens
    val focusManager = LocalFocusManager.current
    val showHints = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
    val slots = splitActions(actions, maxVisibleActions)
    val hasOverflow = slots.overflow.isNotEmpty()
    val hasNavigation = navigationItems.isNotEmpty()
    require(onBack == null || !hasNavigation) {
        "The left end of the bottom bar is either the navigation menu or Back, not both: " +
            "pass navigationItems or onBack"
    }

    var menuState by remember { mutableStateOf(PanelState.Hidden) }
    var navOpen by remember { mutableStateOf(false) }
    var navPeek by remember { mutableStateOf(false) }
    var restoreMoreFocus by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val moreFocus = remember { FocusRequester() }

    fun closeOverflow() {
        menuState = PanelState.Hidden
        restoreMoreFocus = true
    }
    fun closeNavigation() {
        navOpen = false
        restoreFocus = true
    }
    fun openOverflow() {
        navOpen = false
        menuState = PanelState.Open
    }
    fun peekOverflow() {
        if (!navOpen && menuState == PanelState.Hidden) menuState = PanelState.Peek
    }
    fun openNavigation() {
        menuState = PanelState.Hidden
        navPeek = false
        navOpen = true
    }

    val menuController = remember(hasOverflow) {
        if (hasOverflow) Bb10MenuController(peek = { peekOverflow() }, open = { openOverflow() }) else null
    }

    val inPage = LocalBb10InPage.current
    if (autoFocus && !inPage) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focusManager.moveFocus(FocusDirection.Next) }
        }
    }
    LaunchedEffect(restoreMoreFocus) {
        if (restoreMoreFocus) {
            delay(PANEL_EXIT_MS)
            runCatching { moreFocus.requestFocus() }
            restoreMoreFocus = false
        }
    }
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            delay(PANEL_EXIT_MS)
            runCatching { focusManager.moveFocus(FocusDirection.Next) }
            restoreFocus = false
        }
    }
    BackHandler(enabled = onBack != null && search == null && menuState == PanelState.Hidden && !navOpen) {
        onBack?.invoke()
    }
    BackHandler(enabled = selectionMode && search == null && menuState == PanelState.Hidden && !navOpen) {
        onCancelSelection()
    }
    BackHandler(enabled = search != null) { search?.onClose?.invoke() }
    BackHandler(enabled = menuState != PanelState.Hidden || navOpen) {
        if (menuState != PanelState.Hidden) closeOverflow() else closeNavigation()
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(c.surface)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (e.key == Key.Menu && hasOverflow) {
                    if (menuState == PanelState.Open) closeOverflow() else openOverflow()
                    return@onKeyEvent true
                }
                if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return@onKeyEvent false
                val action = actions.firstOrNull { it.enabled && shortcutMatches(it.shortcut, e.key) }
                if (action != null) {
                    action.onClick()
                    true
                } else false
            }
    ) {
        val strings = Bb10.strings
        val leading = when {
            selectionMode -> Bb10Action("cancel", strings.cancel, Bb10Icons.Cancel, onClick = onCancelSelection)
            onBack != null -> Bb10Action("back", strings.back, Bb10Icons.ChevronLeft, onClick = onBack)
            hasNavigation -> Bb10Action("menu", strings.navigationMenu, Bb10Icons.Menu) {
                if (navOpen) closeNavigation() else openNavigation()
            }
            else -> null
        }
        val navWidth = minOf(maxWidth * 0.76f, 320.dp)
        val peekWidth = 56.dp
        val pushAnim = remember { Animatable(0.dp, Dp.VectorConverter) }
        var dragging by remember { mutableStateOf(false) }
        val target = if (!navOpen) 0.dp else if (navPeek) peekWidth else navWidth
        var releaseSpeed by remember { mutableStateOf(0f) }
        LaunchedEffect(target, dragging) {
            if (!dragging) {
                val d = target.value - pushAnim.value.value
                val toward = if (d > 0f) releaseSpeed else -releaseSpeed
                releaseSpeed = 0f
                pushAnim.animateTo(target, tween(navSettleMs(kotlin.math.abs(d), toward), easing = NavSettleEasing))
            }
        }
        val push = pushAnim.value
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val density = androidx.compose.ui.platform.LocalDensity.current
        var lastDx by remember { mutableStateOf(0f) }
        var dragFromClosed by remember { mutableStateOf(true) }
        var lastMoveAt by remember { mutableStateOf(0L) }
        val tracker = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }
        fun dragBy(dx: Float) {
            if (dx != 0f) {
                lastDx = dx
                lastMoveAt = android.os.SystemClock.uptimeMillis()
            }
            val v = (pushAnim.value + with(density) { dx.toDp() }).coerceIn(0.dp, navWidth)
            scope.launch { pushAnim.snapTo(v) }
        }
        fun settle() {
            val v = pushAnim.value
            val still = android.os.SystemClock.uptimeMillis() - lastMoveAt > NAV_STILL_MS
            val dir = if (still) 0f else lastDx
            val speed = if (still) 0f else with(density) { tracker.calculateVelocity().x.toDp().value }
            tracker.resetTracking()
            val to = navRest(v.value, navWidth.value, peekWidth.value, dir, dragFromClosed, speed).dp
            releaseSpeed = speed
            menuState = PanelState.Hidden
            navPeek = to == peekWidth
            if (to == 0.dp && navOpen) closeNavigation() else if (to > 0.dp) navOpen = true
            dragging = false
        }

        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (hasNavigation && !selectionMode) Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragging = true
                                dragFromClosed = pushAnim.value <= 0.5.dp
                                tracker.resetTracking()
                            },
                            onDragEnd = { settle() },
                            onDragCancel = { settle() },
                            onHorizontalDrag = { ch, dx ->
                                tracker.addPointerInputChange(ch)
                                ch.consume()
                                dragBy(dx)
                            },
                        )
                    } else Modifier
                ),
        ) {
        if (hasNavigation && !selectionMode && (navOpen || push > 0.dp)) {
            Bb10NavDrawer(
                open = navOpen,
                items = navigationItems,
                selectedIndex = selectedNavigation,
                onSelect = onNavigate,
                onDismiss = { closeNavigation() },
                width = navWidth,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Box(Modifier.offset(x = push - 9.dp).width(9.dp).fillMaxHeight().background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent,
                    androidx.compose.ui.graphics.Color(0x40000000), androidx.compose.ui.graphics.Color(0x99000000)))))
        }

        CompositionLocalProvider(LocalBb10Menu provides menuController) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset(x = push)
                    .background(c.background),
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (showTitle) Bb10TitleBar(
                        title = title,
                        subtitle = subtitle,
                        dismissAction = dismissAction,
                        acceptAction = acceptAction,
                        search = search,
                        tabs = tabs,
                        selectedTab = selectedTab,
                        onTabSelect = onTabSelect,
                    )
                    val hasMiddle = slots.primary != null || slots.visible.isNotEmpty()
                    val barMetrics = LocalBarMetrics.current ?: bb10BarMetrics()
                    val hasBar = hasMiddle || hasOverflow || leading != null
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = if (hasMiddle) barMetrics.height else 0.dp),
                            content = content,
                        )
                        if (hasBar) CompositionLocalProvider(LocalBarMetrics provides barMetrics) {
                            Bb10ActionBar(
                                slots = slots,
                                modifier = Modifier.align(Alignment.BottomCenter),
                                leading = leading,
                                leadingIsBack = onBack != null && !selectionMode,
                                leadingShowsLabel = selectionMode,
                                moreFocusRequester = moreFocus,
                                onMore = { openOverflow() },
                            )
                        }
                    }
                }
                if (push > 0.dp) {
                    val progress = (push / navWidth).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(c.scrim.copy(alpha = c.scrim.alpha * progress))
                            .pointerInput(Unit) { detectTapGestures { closeNavigation() } }
                    )
                }
            }
        }
        }

        if (hasOverflow) {
            Bb10OverflowMenu(
                state = menuState,
                actions = slots.overflow,
                showHints = showHints,
                onDismiss = { closeOverflow() },
            )
        }
    }
}

private const val PANEL_EXIT_MS = 220L

fun navRest(v: Float, width: Float, peek: Float, dir: Float, fromClosed: Boolean, speedDp: Float = 0f): Float {
    val iconsUpTo = width * 0.4f
    return when {
        speedDp > NAV_FLING_DP -> width
        speedDp < -NAV_FLING_DP -> 0f
        dir < 0f -> 0f
        dir > 0f && !fromClosed -> width
        v < peek * 0.5f -> 0f
        v <= iconsUpTo -> peek
        else -> width
    }
}

const val NAV_STILL_MS = 90L

const val NAV_FLING_DP = 500f

val NavSettleEasing = androidx.compose.animation.core.CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

fun navSettleMs(dist: Float, speed: Float): Int {
    if (dist <= 0f) return 1
    if (speed <= 0f) return 200
    return (3000f * dist / speed).toInt().coerceIn(110, 320)
}
