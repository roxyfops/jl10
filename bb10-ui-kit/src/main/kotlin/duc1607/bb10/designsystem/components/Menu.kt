package duc1607.bb10.designsystem.components

import androidx.compose.runtime.compositionLocalOf

internal class Bb10MenuController(
    val peek: () -> Unit,
    val open: () -> Unit,
)

internal val LocalBb10Menu = compositionLocalOf<Bb10MenuController?> { null }
