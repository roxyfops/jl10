package com.bb10.kit

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import duc1607.bb10.designsystem.components.Bb10ContextMenuState
import duc1607.bb10.designsystem.components.Bb10Divider
import duc1607.bb10.designsystem.components.Bb10Icon
import duc1607.bb10.designsystem.components.Bb10OnHighlight
import duc1607.bb10.designsystem.components.Bb10Text
import duc1607.bb10.designsystem.components.bb10Clickable
import duc1607.bb10.designsystem.components.bb10ContextClickable
import duc1607.bb10.designsystem.components.rememberBb10Highlight
import duc1607.bb10.designsystem.theme.Bb10
import java.util.Calendar

val Jl10Red = Color(0xFFE35B5B)

@Composable
fun Jl10DayHeader(label: String, color: Color? = null, onClick: (() -> Unit)? = null) {
    val c = Bb10.colors
    val hl = rememberBb10Highlight()
    Box(
        Modifier.fillMaxWidth().background(c.surfaceVariant)
            .let { if (onClick != null) it.bb10Clickable(highlight = hl, flat = true, onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Bb10OnHighlight(hl) {
            Bb10Text(label, style = Bb10.typography.secondary, color = color ?: c.textSecondary, maxLines = 1)
        }
    }
}

@Composable
fun Jl10HubRow(
    title: String,
    text: String?,
    time: String?,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: Bb10ContextMenuState? = null,
    onPick: () -> Unit = {},
    bold: Boolean = false,
    dim: Boolean = false,
    strike: Boolean = false,
    flag: Boolean = false,
    textColor: Color? = null,
    timeColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Bb10.colors
    val t = Bb10.typography
    val hl = rememberBb10Highlight()
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .let {
                    if (menu != null) it.bb10ContextClickable(menu, hl, onPick = onPick, onClick = onClick)
                    else it.bb10Clickable(highlight = hl, flat = true, onClick = onClick)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Bb10OnHighlight(hl) {
                if (leading != null) Box(Modifier.padding(horizontal = 14.dp).size(34.dp), contentAlignment = Alignment.Center) { leading() }
                else Box(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Bb10Text(
                        title,
                        style = t.body.copy(
                            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                            fontSize = t.body.fontSize * 1.12f,
                            textDecoration = if (strike) TextDecoration.LineThrough else null,
                        ),
                        color = if (dim && !hl.active) c.textSecondary else Color.Unspecified,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (!text.isNullOrEmpty()) Bb10Text(text, style = t.secondary,
                        color = if (hl.active) Color.Unspecified else textColor ?: c.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.fillMaxHeight().padding(start = 8.dp, end = 10.dp, top = 10.dp), horizontalAlignment = Alignment.End) {
                    if (!time.isNullOrEmpty()) Bb10Text(time, style = t.caption,
                        color = if (hl.active) Color.Unspecified else timeColor ?: c.textSecondary, maxLines = 1)
                    if (flag) Bb10Text("!", style = t.title.copy(fontWeight = FontWeight.Bold), color = Jl10Red)
                }
                Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            }
        }
        Bb10Divider()
    }
}

@Composable
fun Jl10Glyph(icon: ImageVector, tint: Color, size: Dp = 28.dp) {
    Bb10Icon(icon, tint = tint, size = size)
}

@Composable
fun Jl10Empty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Bb10Text(text, style = Bb10.typography.body, color = Bb10.colors.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
fun Jl10PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = Bb10.typography.body,
    singleLine: Boolean = false,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Default,
    onDone: (() -> Unit)? = null,
) {
    val c = Bb10.colors
    var tfv by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length)))
    }
    if (tfv.text != value) tfv = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    androidx.compose.foundation.text.BasicTextField(
        tfv, { v -> tfv = v; if (v.text != value) onValueChange(v.text) },
        if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier,
        textStyle = style.copy(color = c.textPrimary),
        cursorBrush = SolidColor(c.accent),
        singleLine = singleLine,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onDone?.invoke() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Bb10Text(placeholder, style = style, color = c.textSecondary)
                inner()
            }
        },
    )
}

object Jl10Time {
    fun row(c: Context, t: Long): String =
        if (DateUtils.isToday(t)) DateFormat.format(if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a", t).toString()
        else DateFormat.format("MMM d", t).toString()

    fun clock(c: Context, t: Long): String =
        DateFormat.format(if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a", t).toString()

    fun day(t: Long): String = when {
        DateUtils.isToday(t) -> "Today"
        DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> DateFormat.format("EEEE, MMMM d, yyyy", t).toString()
    }

    fun dayKey(t: Long): Long = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000L + it.get(Calendar.DAY_OF_YEAR) }

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

object Jl10Icons {
    val Share = stroked("share") {
        circle(17f, 5.5f, 2.5f); circle(6f, 12f, 2.5f); circle(17f, 18.5f, 2.5f)
        line(8.2f, 10.8f, 14.8f, 6.8f); line(8.2f, 13.2f, 14.8f, 17.2f)
    }
    val Sort = stroked("sort") {
        line(4f, 7f, 14f, 7f); line(4f, 12f, 11f, 12f); line(4f, 17f, 8f, 17f)
        line(18f, 5f, 18f, 19f); moveTo(15f, 16f); lineTo(18f, 19f); lineTo(21f, 16f)
    }
    val Flag = stroked("flag") {
        line(6f, 3f, 6f, 21f)
        moveTo(6f, 4f); lineTo(18f, 4f); lineTo(15f, 8.5f); lineTo(18f, 13f); lineTo(6f, 13f)
    }
    val Note = stroked("note") {
        moveTo(5f, 3.5f); lineTo(15f, 3.5f); lineTo(19f, 7.5f); lineTo(19f, 20.5f); lineTo(5f, 20.5f); close()
        moveTo(15f, 3.5f); lineTo(15f, 7.5f); lineTo(19f, 7.5f)
        line(8f, 11f, 16f, 11f); line(8f, 14f, 16f, 14f); line(8f, 17f, 13f, 17f)
    }
    val Event = stroked("event") {
        moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        line(4f, 10f, 20f, 10f); line(8f, 3.5f, 8f, 7.5f); line(16f, 3.5f, 16f, 7.5f)
        moveTo(8f, 13f); lineTo(10f, 13f); moveTo(14f, 13f); lineTo(16f, 13f); moveTo(8f, 16.5f); lineTo(10f, 16.5f)
    }
    val Today = stroked("today") {
        moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        line(4f, 10f, 20f, 10f); line(8f, 3.5f, 8f, 7.5f); line(16f, 3.5f, 16f, 7.5f)
        moveTo(10f, 13f); lineTo(14f, 13f); lineTo(14f, 17f); lineTo(10f, 17f); close()
    }
    val Clock = stroked("clock") {
        circle(12f, 12f, 8.5f); moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f)
    }
    val Phone = stroked("phone") {
        moveTo(6.5f, 3.5f); lineTo(9.5f, 3.5f); lineTo(11f, 8f); lineTo(8.8f, 9.6f)
        quadTo(10.5f, 13.5f, 14.4f, 15.2f); lineTo(16f, 13f); lineTo(20.5f, 14.5f); lineTo(20.5f, 17.5f)
        quadTo(20.5f, 20.5f, 17.5f, 20.5f); quadTo(4f, 19.5f, 3.5f, 6.5f); quadTo(3.5f, 3.5f, 6.5f, 3.5f); close()
    }
    val Person = stroked("person") {
        circle(12f, 8f, 4f)
        moveTo(4.5f, 20.5f); quadTo(5f, 13.5f, 12f, 13.5f); quadTo(19f, 13.5f, 19.5f, 20.5f)
    }
    val People = stroked("people") {
        circle(9f, 8.5f, 3.5f)
        moveTo(2.5f, 19.5f); quadTo(3f, 13.5f, 9f, 13.5f); quadTo(15f, 13.5f, 15.5f, 19.5f)
        moveTo(14.5f, 5.3f); quadTo(18.5f, 5.5f, 18.5f, 8.5f); quadTo(18.5f, 11.3f, 15.5f, 11.8f)
        moveTo(17f, 13.8f); quadTo(21f, 14.5f, 21.5f, 19.5f)
    }
    val Message = stroked("message") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(10f, 16f); lineTo(6f, 20f); lineTo(6f, 16f); lineTo(4f, 16f); close()
    }
    val Keypad = ImageVector.Builder("keypad", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            for (y in listOf(4f, 9.5f, 15f)) for (x in listOf(5f, 10.5f, 16f)) {
                moveTo(x, y); horizontalLineTo(x + 3f); verticalLineTo(y + 3f); horizontalLineTo(x); close()
            }
            moveTo(10.5f, 20f); horizontalLineTo(13.5f); verticalLineTo(23f); horizontalLineTo(10.5f); close()
        }
    }.build()
    val History = stroked("history") {
        moveTo(4.5f, 12f); arcTo(7.5f, 7.5f, 0f, true, true, 6.7f, 17.3f)
        moveTo(2.5f, 10f); lineTo(4.5f, 12.5f); lineTo(6.8f, 10f)
        moveTo(12f, 8f); lineTo(12f, 12f); lineTo(14.8f, 14f)
    }
    val Undo = stroked("undo") {
        moveTo(8f, 5f); lineTo(4f, 9f); lineTo(8f, 13f)
        moveTo(4f, 9f); lineTo(14f, 9f); quadTo(20f, 9f, 20f, 14.5f); quadTo(20f, 20f, 14f, 20f); lineTo(9f, 20f)
    }
    val Location = stroked("location") {
        moveTo(12f, 21f); quadTo(5f, 13.5f, 5f, 9.5f); arcTo(7f, 7f, 0f, false, true, 19f, 9.5f); quadTo(19f, 13.5f, 12f, 21f); close()
        circle(12f, 9.5f, 2.5f)
    }
    val Bell = stroked("bell") {
        moveTo(6f, 17f); lineTo(6f, 11f); arcTo(6f, 6f, 0f, false, true, 18f, 11f); lineTo(18f, 17f)
        line(4f, 17f, 20f, 17f); moveTo(10f, 20f); lineTo(14f, 20f)
    }
    val Mute = stroked("mute") {
        moveTo(9f, 4f); arcTo(3f, 3f, 0f, false, true, 15f, 4f); lineTo(15f, 12f); arcTo(3f, 3f, 0f, false, true, 9f, 12f); close()
        moveTo(5.5f, 11f); quadTo(5.5f, 17f, 12f, 17f); quadTo(18.5f, 17f, 18.5f, 11f); line(12f, 17f, 12f, 21f)
        line(4f, 3f, 20f, 21f)
    }
    val Mic = stroked("mic") {
        moveTo(9f, 4f); arcTo(3f, 3f, 0f, false, true, 15f, 4f); lineTo(15f, 12f); arcTo(3f, 3f, 0f, false, true, 9f, 12f); close()
        moveTo(5.5f, 11f); quadTo(5.5f, 17f, 12f, 17f); quadTo(18.5f, 17f, 18.5f, 11f); line(12f, 17f, 12f, 21f)
    }
    val Speaker = stroked("speaker") {
        moveTo(3f, 10f); lineTo(6f, 10f); lineTo(11f, 6f); lineTo(11f, 18f); lineTo(6f, 14f); lineTo(3f, 14f); close()
        moveTo(14f, 9f); quadTo(16.5f, 12f, 14f, 15f)
        moveTo(17f, 6.5f); quadTo(21f, 12f, 17f, 17.5f)
    }
    val Pause = stroked("pause") { line(9f, 5f, 9f, 19f); line(15f, 5f, 15f, 19f) }
    val Camera = stroked("camera") {
        moveTo(3f, 8f); lineTo(7.5f, 8f); lineTo(9f, 5.5f); lineTo(15f, 5.5f); lineTo(16.5f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        circle(12f, 13f, 3.5f)
    }
    val Video = stroked("video") {
        moveTo(3f, 7f); lineTo(15f, 7f); lineTo(15f, 17f); lineTo(3f, 17f); close()
        moveTo(15f, 10.5f); lineTo(21f, 7f); lineTo(21f, 17f); lineTo(15f, 13.5f)
    }
    val Image = stroked("image") {
        moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close()
        moveTo(3.5f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(15.5f, 12.5f); lineTo(20.5f, 17f)
        circle(15.5f, 8.8f, 1.5f)
    }
    val Link = stroked("link") {
        moveTo(10f, 14f); lineTo(14f, 10f)
        moveTo(11f, 7f); lineTo(13f, 5f); arcTo(4f, 4f, 0f, false, true, 19f, 11f); lineTo(17f, 13f)
        moveTo(13f, 17f); lineTo(11f, 19f); arcTo(4f, 4f, 0f, false, true, 5f, 13f); lineTo(7f, 11f)
    }
    val Done = stroked("done") { circle(12f, 12f, 8.5f); moveTo(8f, 12.3f); lineTo(10.8f, 15f); lineTo(16.2f, 9.2f) }

    private fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1); lineTo(x2, y2)
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
    }

    private fun stroked(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                pathBuilder = block,
            )
        }.build()
}
