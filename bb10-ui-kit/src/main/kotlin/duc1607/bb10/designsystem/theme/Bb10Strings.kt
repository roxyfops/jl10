package duc1607.bb10.designsystem.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import duc1607.bb10.designsystem.R

@Immutable
data class Bb10Strings(
    val navigationMenu: String,
    val moreActions: String,
    val back: String,
    val cancel: String,
    val search: String,
    val showPassword: String,
    val hidePassword: String,
    val clearText: String,
    val required: String,
    val expanded: String,
    val collapsed: String,
    val displaySize: String,
    val ok: String,
    val today: String,
    val day: String,
    val month: String,
    val year: String,
    val hour: String,
    val minute: String,
) {
    companion object {
        fun from(context: Context) = Bb10Strings(
            navigationMenu = context.getString(R.string.bb10_navigation_menu),
            moreActions = context.getString(R.string.bb10_more_actions),
            back = context.getString(R.string.bb10_back),
            cancel = context.getString(R.string.bb10_cancel),
            search = context.getString(R.string.bb10_search),
            showPassword = context.getString(R.string.bb10_show_password),
            hidePassword = context.getString(R.string.bb10_hide_password),
            clearText = context.getString(R.string.bb10_clear_text),
            required = context.getString(R.string.bb10_required),
            expanded = context.getString(R.string.bb10_expanded),
            collapsed = context.getString(R.string.bb10_collapsed),
            displaySize = context.getString(R.string.bb10_display_size),
            ok = context.getString(R.string.bb10_ok),
            today = context.getString(R.string.bb10_today),
            day = context.getString(R.string.bb10_day),
            month = context.getString(R.string.bb10_month),
            year = context.getString(R.string.bb10_year),
            hour = context.getString(R.string.bb10_hour),
            minute = context.getString(R.string.bb10_minute),
        )
    }
}

@Composable
fun rememberBb10Strings(): Bb10Strings {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { Bb10Strings.from(context) }
}

val LocalBb10Strings = staticCompositionLocalOf<Bb10Strings> { error("Bb10Theme is missing") }
