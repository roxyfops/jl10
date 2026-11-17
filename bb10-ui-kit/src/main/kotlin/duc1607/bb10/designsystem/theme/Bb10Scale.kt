package duc1607.bb10.designsystem.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import duc1607.bb10.designsystem.R
import kotlin.math.abs

@Immutable
class Bb10ScaleStep(@StringRes val nameRes: Int, val scale: Float)

object Bb10Scales {
    val Small = Bb10ScaleStep(R.string.bb10_scale_small, 0.85f)
    val Normal = Bb10ScaleStep(R.string.bb10_scale_normal, 1f)
    val Large = Bb10ScaleStep(R.string.bb10_scale_large, 1.25f)
    val ExtraLarge = Bb10ScaleStep(R.string.bb10_scale_extra_large, 1.5f)
    val Huge = Bb10ScaleStep(R.string.bb10_scale_huge, 2f)

    val all = listOf(Small, Normal, Large, ExtraLarge, Huge)

    fun closest(scale: Float): Bb10ScaleStep = all.minBy { abs(it.scale - scale) }
}

@Stable
class Bb10ScaleState private constructor(
    private val save: (Float) -> Unit,
    initial: Float,
) {
    internal constructor(prefs: SharedPreferences, initial: Float) :
        this({ v: Float -> prefs.edit().putFloat(KEY, v).apply() }, initial)

    var value: Float by mutableFloatStateOf(initial)
        private set

    fun update(scale: Float) {
        val clamped = scale.coerceIn(MinScale, MaxScale)
        value = clamped
        save(clamped)
    }

    fun reload(scale: Float) {
        value = scale.coerceIn(MinScale, MaxScale)
    }

    companion object {
        internal const val PREFS = "bb10_ui_kit"
        internal const val KEY = "ui_scale"
        const val MinScale = 0.5f
        const val MaxScale = 3f

        fun shared(initial: Float, save: (Float) -> Unit): Bb10ScaleState = Bb10ScaleState(save, initial)
    }
}

@Composable
fun rememberBb10UiScale(default: Float = 1f): Bb10ScaleState {
    val context = LocalContext.current
    return remember(context) {
        val prefs = context.applicationContext
            .getSharedPreferences(Bb10ScaleState.PREFS, Context.MODE_PRIVATE)
        Bb10ScaleState(prefs, prefs.getFloat(Bb10ScaleState.KEY, default))
    }
}
