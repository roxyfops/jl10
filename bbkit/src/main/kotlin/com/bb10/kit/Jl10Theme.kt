package com.bb10.kit

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.bb10.launcher.RootShell
import com.bb10.launcher.ui.Ui
import duc1607.bb10.designsystem.components.Bb10LinkItem
import duc1607.bb10.designsystem.icons.Bb10Icons
import duc1607.bb10.designsystem.theme.Bb10
import duc1607.bb10.designsystem.theme.Bb10ScaleState
import duc1607.bb10.designsystem.theme.Bb10Scales
import duc1607.bb10.designsystem.theme.Bb10Theme
import duc1607.bb10.designsystem.theme.LocalBb10Typography

object Jl10 {
    const val SCALE_KEY = "bb10_ui_scale"
    const val THEME_KEY = "bb10_hub_theme"

    fun scale(c: Context): Float =
        runCatching { Settings.Global.getString(c.contentResolver, SCALE_KEY)?.toFloatOrNull() }.getOrNull() ?: 1f

    fun dark(c: Context): Boolean =
        runCatching { Settings.Global.getString(c.contentResolver, THEME_KEY) }.getOrNull() == "dark"

    fun saveScale(c: Context, v: Float) {
        val s = String.format(java.util.Locale.US, "%.2f", v)
        try {
            Settings.Global.putString(c.contentResolver, SCALE_KEY, s)
        } catch (e: SecurityException) {
            RootShell.execAsync(null, "settings put global $SCALE_KEY $s")
        }
    }

    fun openDisplaySettings(c: Context) {
        try {
            c.startActivity(Intent().setClassName("com.bb10.launcher", "com.bb10.launcher.settings.SettingsActivity")
                .putExtra("open", "display").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

@Composable
fun rememberGlobal(key: String, read: (Context) -> Any?): Any? {
    val ctx = LocalContext.current
    var value by remember { mutableStateOf(read(ctx)) }
    DisposableEffect(key) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(self: Boolean) {
                value = read(ctx)
            }
        }
        runCatching { ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, obs) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(obs) } }
    }
    return value
}

@Composable
fun rememberJl10Scale(): Bb10ScaleState {
    val ctx = LocalContext.current
    val state = remember { Bb10ScaleState.shared(Jl10.scale(ctx)) { Jl10.saveScale(ctx, it) } }
    val now = rememberGlobal(Jl10.SCALE_KEY) { Jl10.scale(it) } as Float
    if (now != state.value) state.reload(now)
    return state
}

@Composable
fun rememberJl10Dark(): Boolean = rememberGlobal(Jl10.THEME_KEY) { Jl10.dark(it) } as Boolean

@Composable
fun Jl10Theme(scale: Bb10ScaleState = rememberJl10Scale(), dark: Boolean? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    if (duc1607.bb10.designsystem.components.Bb10Translate.tr == null)
        duc1607.bb10.designsystem.components.Bb10Translate.tr = { com.bb10.launcher.ui.Tr.t(it) }
    val hubDark = rememberJl10Dark()
    Bb10Theme(scale = scale, dark = dark ?: hubDark) {
        val base = Bb10.typography
        val family = remember(Ui.customFontFile) { jl10Font(ctx) }
        val typo = remember(base, family) {
            if (family == null) base else base.copy(
                title = base.title.copy(fontFamily = family),
                header = base.header.copy(fontFamily = family),
                body = base.body.copy(fontFamily = family),
                secondary = base.secondary.copy(fontFamily = family),
                caption = base.caption.copy(fontFamily = family),
                button = base.button.copy(fontFamily = family),
                action = base.action.copy(fontFamily = family),
            )
        }
        CompositionLocalProvider(LocalBb10Typography provides typo, content = content)
    }
}

@OptIn(ExperimentalTextApi::class)
private fun jl10Font(c: Context): FontFamily? {
    if (Ui.customFontFile != null) return FontFamily(androidx.compose.ui.text.font.Typeface(Ui.regular()))
    return runCatching {
        val a = c.assets
        FontFamily(
            Font("fonts/slatepro-light.ttf", a, FontWeight.Light),
            Font("fonts/slatepro.ttf", a, FontWeight.Normal),
            Font("fonts/slatepro-medium.ttf", a, FontWeight.Medium),
            Font("fonts/slatepro-bold.ttf", a, FontWeight.Bold),
        )
    }.getOrNull()
}

@Composable
fun Jl10SystemBars() {
    val act = LocalContext.current as? android.app.Activity ?: return
    val c = Bb10.colors
    androidx.compose.runtime.LaunchedEffect(c) {
        @Suppress("DEPRECATION")
        act.window.statusBarColor = android.graphics.Color.argb(
            (c.surfaceVariant.alpha * 255).toInt(), (c.surfaceVariant.red * 255).toInt(),
            (c.surfaceVariant.green * 255).toInt(), (c.surfaceVariant.blue * 255).toInt())
        @Suppress("DEPRECATION")
        act.window.navigationBarColor = android.graphics.Color.argb(255,
            (c.surface.red * 255).toInt(), (c.surface.green * 255).toInt(), (c.surface.blue * 255).toInt())
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        act.window.insetsController?.setSystemBarsAppearance(if (c.isDark) 0 else light, light)
    }
}

@Composable
fun Jl10DisplaySizeLink() {
    val ctx = LocalContext.current
    val scale = rememberGlobal(Jl10.SCALE_KEY) { Jl10.scale(it) } as Float
    Bb10LinkItem(
        title = "Display size",
        subtitle = stringResource(Bb10Scales.closest(scale).nameRes) + " · set in JL10 Settings for every BB10 app",
        icon = Bb10Icons.Sliders,
        onClick = { Jl10.openDisplaySettings(ctx) },
    )
}
