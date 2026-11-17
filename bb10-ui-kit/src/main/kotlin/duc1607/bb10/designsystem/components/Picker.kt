package duc1607.bb10.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.theme.Bb10
import duc1607.bb10.designsystem.R
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.time.LocalDate
import java.time.LocalTime

private val PickerRowHeight = 40.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Bb10PickerColumn(
    labels: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    subLabel: ((Int) -> String?)? = null,
    visibleRows: Int = 5,
    focusRequester: FocusRequester? = null,

    onConfirm: (() -> Unit)? = null,

    endDivider: Boolean = false,
) {
    val c = Bb10.colors
    val t = Bb10.typography
    val rowPx = with(LocalDensity.current) { PickerRowHeight.toPx() }
    val padRows = visibleRows / 2
    val last = (labels.size - 1).coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, last))
    val fling = rememberSnapFlingBehavior(listState)
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentChange by rememberUpdatedState(onSelectedChange)
    val currentConfirm by rememberUpdatedState(onConfirm)

    val centered by remember(labels.size) {
        derivedStateOf {
            val bump = if (listState.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0
            (listState.firstVisibleItemIndex + bump).coerceIn(0, last)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && centered != currentSelected) currentChange(centered)
        }
    }
    LaunchedEffect(selectedIndex, labels.size) {
        val target = selectedIndex.coerceIn(0, last)
        if (!listState.isScrollInProgress && centered != target) listState.animateScrollToItem(target)
    }

    Box(
        modifier
            .height(PickerRowHeight * visibleRows)
            .background(c.surfaceVariant)
            .semantics {
                if (description != null) contentDescription = description
                stateDescription = labels.getOrElse(centered) { "" }
            }
            .onKeyEvent { e ->
                val isDone = e.key == Key.Enter || e.key == Key.NumPadEnter ||
                    e.key == Key.DirectionCenter || e.key == Key.Spacebar
                if (isDone) {
                    val confirm = currentConfirm ?: return@onKeyEvent false
                    if (e.type == KeyEventType.KeyUp) confirm()
                    return@onKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> if (centered > 0) {
                        currentChange(centered - 1)
                        true
                    } else false
                    Key.DirectionDown -> if (centered < last) {
                        currentChange(centered + 1)
                        true
                    } else false
                    else -> false
                }
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(true, source)
            .drawBehind {
                val top = padRows * rowPx
                if (focused) {
                    drawRect(
                        c.accent.copy(alpha = 0.28f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, rowPx),
                    )
                }
                val line = 1.5.dp.toPx()
                drawRect(c.accent, topLeft = Offset(0f, top), size = Size(size.width, line))
                drawRect(c.accent, topLeft = Offset(0f, top + rowPx - line), size = Size(size.width, line))
                if (endDivider) {
                    val edge = 8.dp.toPx()
                    val near = 6.dp.toPx()
                    val w = 1.dp.toPx()
                    val x = size.width - w
                    val above = top - edge - near
                    drawRect(c.divider, Offset(x, edge), Size(w, above))
                    drawRect(c.divider, Offset(x, top + rowPx + near), Size(w, above))
                }
            },
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = PickerRowHeight * padRows),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(labels) { index, label ->
                val nearness: GraphicsLayerScope.() -> Float = {
                    val position = listState.firstVisibleItemIndex * rowPx +
                        listState.firstVisibleItemScrollOffset
                    (1f - abs(index * rowPx - position) / rowPx).coerceIn(0f, 1f)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(PickerRowHeight)
                        .pointerInput(index) {
                            detectTapGestures {
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Bb10Text(
                            label,
                            style = t.button,
                            color = c.textPrimary,
                            modifier = Modifier.graphicsLayer {
                                alpha = 0.6f + 0.4f * nearness()
                            },
                        )
                        val sub = subLabel?.invoke(index)
                        if (sub != null) {
                            Bb10Text(
                                sub,
                                style = t.caption,
                                color = c.textSecondary,
                                maxLines = 1,
                                modifier = Modifier.graphicsLayer { alpha = nearness() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Bb10DatePicker(
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    yearRange: IntRange = (date.year - 50)..(date.year + 50),
    initialFocus: FocusRequester? = null,
    onConfirm: (() -> Unit)? = null,
) {
    val strings = Bb10.strings
    val years = remember(yearRange, date.year) {
        (minOf(yearRange.first, date.year)..maxOf(yearRange.last, date.year)).toList()
    }
    val monthNames = stringArrayResource(R.array.bb10_months)
    val weekdayNames = stringArrayResource(R.array.bb10_weekdays)
    val length = date.lengthOfMonth()

    fun keepDay(target: LocalDate): LocalDate =
        target.withDayOfMonth(minOf(date.dayOfMonth, target.lengthOfMonth()))

    Row(modifier.fillMaxWidth()) {
        Bb10PickerColumn(
            labels = (1..length).map { "%02d".format(it) },
            selectedIndex = date.dayOfMonth - 1,
            onSelectedChange = { onDateChange(date.withDayOfMonth(it + 1)) },
            modifier = Modifier.weight(1f),
            description = strings.day,
            onConfirm = onConfirm,
            subLabel = { i -> weekdayNames[date.withDayOfMonth(i + 1).dayOfWeek.value - 1] },
            focusRequester = initialFocus,
            endDivider = true,
        )
        Bb10PickerColumn(
            labels = (1..12).map { "%02d".format(it) },
            selectedIndex = date.monthValue - 1,
            onSelectedChange = { onDateChange(keepDay(date.withDayOfMonth(1).withMonth(it + 1))) },
            modifier = Modifier.weight(1f),
            description = strings.month,
            onConfirm = onConfirm,
            subLabel = { i -> monthNames[i] },
            endDivider = true,
        )
        Bb10PickerColumn(
            labels = years.map { it.toString() },
            selectedIndex = years.indexOf(date.year).coerceAtLeast(0),
            onSelectedChange = { onDateChange(keepDay(date.withDayOfMonth(1).withYear(years[it]))) },
            modifier = Modifier.weight(1f),
            description = strings.year,
            onConfirm = onConfirm,
        )
    }
}

@Composable
fun Bb10TimePicker(
    time: LocalTime,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    is24Hour: Boolean = true,
    minuteStep: Int = 1,
    initialFocus: FocusRequester? = null,
    onConfirm: (() -> Unit)? = null,
) {
    val strings = Bb10.strings
    val step = minuteStep.coerceIn(1, 30)
    val minutes = remember(step) { (0 until 60 step step).toList() }
    val pm = time.hour >= 12
    val amPm = stringArrayResource(R.array.bb10_am_pm).toList()

    Row(modifier.fillMaxWidth()) {
        if (is24Hour) {
            Bb10PickerColumn(
                labels = (0..23).map { "%02d".format(it) },
                selectedIndex = time.hour,
                onSelectedChange = { onTimeChange(time.withHour(it)) },
                modifier = Modifier.weight(1f),
                description = strings.hour,
                onConfirm = onConfirm,
                focusRequester = initialFocus,
                endDivider = true,
            )
        } else {
            val hour12 = (time.hour + 11) % 12 + 1
            Bb10PickerColumn(
                labels = (1..12).map { it.toString() },
                selectedIndex = hour12 - 1,
                onSelectedChange = { index ->
                    val h12 = index + 1
                    onTimeChange(time.withHour(h12 % 12 + if (pm) 12 else 0))
                },
                modifier = Modifier.weight(1f),
                description = strings.hour,
                onConfirm = onConfirm,
                focusRequester = initialFocus,
                endDivider = true,
            )
        }
        Bb10PickerColumn(
            labels = minutes.map { "%02d".format(it) },
            selectedIndex = time.minute / step,
            onSelectedChange = { onTimeChange(time.withMinute(minutes[it])) },
            modifier = Modifier.weight(1f),
            description = strings.minute,
            onConfirm = onConfirm,
            endDivider = !is24Hour,
        )
        if (!is24Hour) {
                Bb10PickerColumn(
                labels = amPm,
                selectedIndex = if (pm) 1 else 0,
                onSelectedChange = { index ->
                    val h = time.hour % 12
                    onTimeChange(time.withHour(h + if (index == 1) 12 else 0))
                },
                modifier = Modifier.weight(1f),
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
fun Bb10DatePickerField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    yearRange: IntRange = (date.year - 50)..(date.year + 50),
) {
    val months = stringArrayResource(R.array.bb10_months)
    val text = stringResource(R.string.bb10_date_pattern, date.dayOfMonth, months[date.monthValue - 1], date.year)
    Bb10DropdownPanel(label, text, modifier, enabled) { close ->
        val focus = remember { FocusRequester() }
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            withFrameNanos { }
            inputMode.focusForKeys(focus)
        }
        Bb10DatePicker(date, onDateChange, yearRange = yearRange, initialFocus = focus, onConfirm = close)
    }
}

@Composable
fun Bb10TimePickerField(
    label: String,
    time: LocalTime,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    is24Hour: Boolean = true,
    minuteStep: Int = 1,
) {
    val amPm = stringArrayResource(R.array.bb10_am_pm)
    val text = if (is24Hour) {
        stringResource(R.string.bb10_time_24h_pattern, time.hour, time.minute)
    } else {
        stringResource(
            R.string.bb10_time_12h_pattern,
            (time.hour + 11) % 12 + 1, time.minute, amPm[if (time.hour >= 12) 1 else 0],
        )
    }
    Bb10DropdownPanel(label, text, modifier, enabled) { close ->
        val focus = remember { FocusRequester() }
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            withFrameNanos { }
            inputMode.focusForKeys(focus)
        }
        Bb10TimePicker(
            time, onTimeChange,
            is24Hour = is24Hour, minuteStep = minuteStep, initialFocus = focus, onConfirm = close,
        )
    }
}

@Composable
fun Bb10DatePickerDialog(
    title: String,
    date: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String? = null,
    dismissText: String? = null,
    showToday: Boolean = true,
    yearRange: IntRange = (date.year - 50)..(date.year + 50),
) {
    val strings = Bb10.strings
    var picked by remember { mutableStateOf(date) }
    Bb10Dialog(
        title = title,
        confirmText = confirmText ?: strings.ok,
        onConfirm = { onConfirm(picked) },
        onDismiss = onDismiss,
        modifier = modifier,
        dismissText = dismissText ?: strings.cancel,
        neutralText = if (showToday) strings.today else null,
        onNeutral = { picked = LocalDate.now() },
        focusContent = true,
    ) { focusConfirm ->
        val focus = remember { FocusRequester() }
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            withFrameNanos { }
            inputMode.focusForKeys(focus)
        }
        Bb10DatePicker(
            picked, { picked = it },
            yearRange = yearRange, initialFocus = focus, onConfirm = focusConfirm,
        )
    }
}

@Composable
fun Bb10TimePickerDialog(
    title: String,
    time: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String? = null,
    dismissText: String? = null,
    is24Hour: Boolean = true,
    minuteStep: Int = 1,
) {
    val strings = Bb10.strings
    var picked by remember { mutableStateOf(time) }
    Bb10Dialog(
        title = title,
        confirmText = confirmText ?: strings.ok,
        onConfirm = { onConfirm(picked) },
        onDismiss = onDismiss,
        modifier = modifier,
        dismissText = dismissText ?: strings.cancel,
        focusContent = true,
    ) { focusConfirm ->
        val focus = remember { FocusRequester() }
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            withFrameNanos { }
            inputMode.focusForKeys(focus)
        }
        Bb10TimePicker(
            picked, { picked = it },
            is24Hour = is24Hour, minuteStep = minuteStep, initialFocus = focus, onConfirm = focusConfirm,
        )
    }
}
