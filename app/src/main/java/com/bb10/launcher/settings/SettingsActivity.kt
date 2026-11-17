package com.bb10.launcher.settings

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bb10.kit.Jl10SystemBars
import com.bb10.kit.Jl10Theme
import com.bb10.kit.rememberJl10Scale
import com.bb10.launcher.LauncherApp
import com.bb10.launcher.Prefs
import com.bb10.launcher.R
import com.bb10.launcher.icons.IconPackManager
import com.bb10.launcher.ui.Ui
import duc1607.bb10.designsystem.components.Bb10Checkbox
import duc1607.bb10.designsystem.components.Bb10Dropdown
import duc1607.bb10.designsystem.components.Bb10InputDialog
import duc1607.bb10.designsystem.components.Bb10LazyColumn
import duc1607.bb10.designsystem.components.Bb10LinkItem
import duc1607.bb10.designsystem.components.Bb10ListHeader
import duc1607.bb10.designsystem.components.Bb10ListItem
import duc1607.bb10.designsystem.components.Bb10PushHost
import duc1607.bb10.designsystem.components.Bb10ScalePicker
import duc1607.bb10.designsystem.components.Bb10Scaffold
import duc1607.bb10.designsystem.components.Bb10Search
import duc1607.bb10.designsystem.components.Bb10Text
import duc1607.bb10.designsystem.components.Bb10Toggle
import duc1607.bb10.designsystem.theme.Bb10

class SettingsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN = "open"
    }

    private var version by mutableIntStateOf(0)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        LauncherApp.get().ensureInit()
        val open = intent.getStringExtra(EXTRA_OPEN)
        setContent {
            Jl10Theme {
                Jl10SystemBars()
                val font = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) SettingsTools.importFont(this, uri) { version++ }
                }
                SettingsRoot(version, if (open == "qs_diag") null else open,
                    pickFont = { runCatching { font.launch(arrayOf("*/*")) } },
                    refresh = { version++ })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        version++
    }
}

private sealed interface Item

private class Page(val key: String, val title: String, val icon: Int, val summary: String?, val items: List<Item>) : Item

private class Header(val title: String) : Item

private class Switch(
    val key: String, val title: String, val summary: String? = null, val default: Boolean,
    val dependsOn: String? = null,
) : Item

private class Choice(
    val key: String, val title: String, val default: String,
    val dependsOn: String? = null,
    val options: (Context) -> List<Pair<String, String>>,
) : Item

private class NumberEntry(val key: String, val title: String, val summary: String, val default: String, val dependsOn: String? = null) : Item
private class TextEntry(val key: String, val title: String, val summary: String, val default: String) : Item

private class Multi(val key: String, val title: String, val summary: String?, val byPackage: Boolean) : Item

private class Action(val key: String, val title: String, val summary: String? = null) : Item

private object DisplaySize : Item

private class SizePicker(val key: String, val title: String, val summary: String) : Item

private val SIZE_STEPS = listOf(
    duc1607.bb10.designsystem.theme.Bb10ScaleStep(duc1607.bb10.designsystem.R.string.bb10_scale_small, 0.85f),
    duc1607.bb10.designsystem.theme.Bb10ScaleStep(duc1607.bb10.designsystem.R.string.bb10_scale_normal, 1f),
    duc1607.bb10.designsystem.theme.Bb10ScaleStep(duc1607.bb10.designsystem.R.string.bb10_scale_large, 1.15f),
    duc1607.bb10.designsystem.theme.Bb10ScaleStep(duc1607.bb10.designsystem.R.string.bb10_scale_extra_large, 1.3f),
)

private fun arrays(names: Int, values: Int): (Context) -> List<Pair<String, String>> = { c ->
    c.resources.getStringArray(names).zip(c.resources.getStringArray(values))
}

private fun same(arr: Int, unit: String = ""): (Context) -> List<Pair<String, String>> = { c ->
    c.resources.getStringArray(arr).map { (it + unit) to it }
}

private val parents = mapOf(
    "sysui_keyguard" to false,
    "gestures_enabled" to true,
    "frames_launch" to true,
    "pull_android_panel" to false,
)

private val sections: List<Item> = listOf(
    Page("setup_screen", "Setup", R.drawable.set_setup, null, listOf(
        Action("setup_wizard", "Guided setup", "Sets up everything in one go, step by step"),
        Action("root_setup", "Set everything up with root", "Grants the access below in one go"),
        Header("Status"),
        Action("perm_notif", "Notification access"),
        Action("perm_a11y", "Accessibility service"),
        Action("perm_usage", "Usage access"),
        Action("set_default", "Default home screen"),
        Action("nav_buttons", "Disable Android's gestures, use buttons (Recommended)"),
        Header("More"),
        Action("goto_system", "Root & system modules", "QuickStep and System UI modules"),
    )),
    Page("display", "Display", R.drawable.set_home, "Display size, theme, font", listOf(
        Header("Display size"),
        DisplaySize,
        Header("Lock screen and status bar"),
        SizePicker(Prefs.LOCK_SCALE, "Lock screen size", "The BB10 lock screen and its password screen"),
        SizePicker(Prefs.STATUS_SCALE, "Status bar size", "On the home screen, the lock screen and in every app (in apps, as big as Android's bar has room for)"),
        Header("Theme"),
        Choice("hub_theme", "Theme", "bright", options = arrays(R.array.hub_theme_names, R.array.hub_theme_values)),
        Header("Font"),
        Action("font_pick", "Custom font"),
        Action("font_reset", "Use Slate Pro"),
    )),
    Page("home_screen", "Home Screen", 0, "Grid, icons, wallpaper", listOf(
        Header("Grid"),
        Choice("grid_cols", "Columns", "5", options = same(R.array.grid_sizes)),
        Choice("grid_rows", "Rows", "3", options = same(R.array.grid_sizes)),
        Switch("show_labels", "App names", default = true),
        Multi(Prefs.HIDDEN, "Hidden apps", "Search “private” to find them", byPackage = false),
        Header("Icons"),
        Switch("bb10_icons", "BlackBerry 10 icons", "Where there's a match", true),
        Choice(Prefs.ICON_PACK, "Icon pack", "") { c ->
            listOf("None (app icons)" to "") + IconPackManager.installed(c).map { it[1] to it[0] }
        },
        Choice("icon_shape", "Icon shape", "rounded", options = arrays(R.array.shape_names, R.array.shape_values)),
        Header("Wallpaper"),
        Action("wallpaper", "Wallpaper", "Home and lock screens"),
        Choice("wall_dim", "Darken wallpaper", "0", options = arrays(R.array.wall_dim_names, R.array.wall_dim_values)),
        Choice("home_text", "Text and icons", "light", options = arrays(R.array.home_text_names, R.array.home_text_values)),
        Header("Reset"),
        Action("reset_layout", "Reset layout", "No folders, A–Z"),
    )),
    Page("frames", "Active Frames", R.drawable.set_frames, "Live frames, messages, animations", listOf(
        Switch("frames_arrange", "Rearrange frames", "Hold to move. Off: most recent first", false),
        Switch("frames_live", "Live frames", "Music, calendar, alarms, downloads, navigation", true),
        Switch("frames_messages", "New messages", "Sender and message in the frame", true),
        Switch("frames_thumbnails", "Snapshots", default = true),
        Switch("frames_launch", "Open apps from a frame", default = true),
        Switch(Prefs.OWN_SPLASH, "JL10 splash screen",
            "Apps open on a grey screen with their icon in the middle, instead of their own splash screens", false, "frames_launch"),
        Switch(Prefs.OWN_SPLASH_SKIP_QUICK, "Skip it for quick apps",
            "An app that's ready by the end of the zoom: the splash just fades into it, no wait or reveal", false, Prefs.OWN_SPLASH),
        Switch("frames_minimize_anim", "Minimise into a frame", default = true),
        Choice("frames_max", "Maximum frames", "0", options = arrays(R.array.frame_counts, R.array.frame_count_values)),
        Switch("frames_kill", "Force-stop when closing", "Some apps dislike it", false),
    )),
    Page("hub", "BlackBerry Hub", R.drawable.set_hub, "Email, Instant Previews, what goes in", listOf(
        Header("Email"),
        Action("mail_accounts", "Email accounts", "Gmail, Outlook, Yahoo, iCloud or any IMAP account, in the Hub"),
        TextEntry(com.bb10.launcher.mail.Mail.SIGNATURE_KEY, "Signature", "Added under every email you write (leave it empty for none)",
            com.bb10.launcher.mail.Mail.DEFAULT_SIGNATURE),
        Header("Calendar"),
        Action("caldav_accounts", "Calendar accounts", "Nextcloud, iCloud, Fastmail or any CalDAV server, in BB10 Calendar"),
        Header("Notifications"),
        Switch("instant_previews", "Instant Previews", "Replaces Android's pop-ups", false),
        Switch("hub_all", "All notifications", "Also system ones", true),
        Switch("hub_split_messages", "Every message on its own", "Telegram, WhatsApp and other chats: each message is its own item, like BBM (not one per chat)", true),
        Switch("hub_keep_history", "Keep messages", "Until you delete them", true),
        Multi(Prefs.HUB_EXCLUDED, "Excluded apps", "Never in the Hub", byPackage = true),
        Action("hub_clear", "Clear history"),
    )),
    Page("statusbar", "Status Bar & Quick Settings", R.drawable.set_statusbar, "Status bar, Quick Settings, volume", listOf(
        Header("Status bar"),
        Switch("bb_status_bar", "On the home screen", default = true),
        Switch("sysui_statusbar", "In every app", "Needs the System UI module", false),
        Switch("clock_24h", "24-hour clock", default = false),
        Switch("clock_seconds", "Show seconds", default = false),
        Switch("battery_percent", "Battery percentage", "Beside the battery icon (the lock screen always shows it)", false),
        Switch(Prefs.NOTIF_ICONS, "Notification icons", "Icons of apps with unread notifications. Off: never shown, anywhere", true),
        Switch("bb_status_logo", "BlackBerry logo", default = true),
        Switch("bb_status_carrier", "Carrier name", default = true),
        Switch("alarm_icon", "Alarm icon", "The alarm clock next to the battery while an alarm is set", true),
        Switch("net_speed", "Network speed", "Upload and download speed, next to the network icons", false),
        Switch("hide_status_apps", "Hide in apps",
            "Apps get the whole screen without going full screen: the status bar takes no room and isn't shown. Swipe down from the top edge for the shade. Needs the System UI module", false),
        Header("Quick Settings"),
        Switch("pull_android_panel", "Use Android's notification panel",
            "Swiping down from the status bar opens Android's own notifications instead of BB10 Quick Settings. The BB10 status bar stays", false),
        Switch("sysui_shade", "Replace the notification shade", "Needs the System UI module", false, dependsOn = "!pull_android_panel"),
        Action("qs_edit", "Toggles", "Choose and order"),
        Switch("qs_brightness", "Brightness slider", default = true),
        Header("Volume"),
        Switch("sysui_volume", "BB10 volume controls", "Needs the System UI module", false),
    )),
    Page("lock", "Lock Screen", R.drawable.set_lock, "BB10 lock screen", listOf(
        Switch("sysui_keyguard", "BB10 lock screen", "Needs the System UI module. Android still checks your password", false),
        NumberEntry("pw_auto_length", "Unlock without Enter", "Your password's length (0 = off). A wrong number fails every try", "0", "sysui_keyguard"),
        Switch("lock_music_tint", "Music bar colour from album art", default = true, dependsOn = "sysui_keyguard"),
        Switch(Prefs.LOCK_CAMERA, "Camera button", "Bottom right of the lock screen", true),
        Choice("lock_column", "Unread apps column", "center", "sysui_keyguard", arrays(R.array.lock_column_names, R.array.lock_column_values)),
        Choice(Prefs.LOCK_TIMEOUT, "Screen timeout", "default", options = arrays(R.array.lock_timeout_names, R.array.lock_timeout_values)),
    )),
    Page("gestures", "Gestures & Keyboard", R.drawable.set_gestures, "Gestures, Type & Go, shortcuts", listOf(
        Header("Buttons"),
        Choice("recents_action", "Recents button", "search", options = arrays(R.array.recents_action_names, R.array.recents_action_values)),
        Header("Gestures"),
        Switch("gestures_enabled", "BB10 gestures in every app", default = true),
        Switch("gesture_home", "Swipe up to minimise", default = true, dependsOn = "gestures_enabled"),
        Switch("gesture_peek", "Up and right to peek at the Hub", default = true, dependsOn = "gestures_enabled"),
        Switch("gesture_predict", "Touch prediction",
            "Swipe up, the Hub and the home screen peek are drawn slightly ahead of your finger, so they keep up with it. Turn off if they overshoot", default = true, dependsOn = "gestures_enabled"),
        Choice("gesture_edge", "Bottom edge height", "20", "gestures_enabled", same(R.array.edge_sizes, " dp")),
        Switch("gesture_haptic", "Vibrate", default = true, dependsOn = "gestures_enabled"),
        Switch("gesture_handle", "Show edge handle", default = false, dependsOn = "gestures_enabled"),
        Header("Keyboard"),
        Switch("kb_type_search", "Type & Go", "Type on the home screen to search", true),
        Switch("kb_shortcuts", "Hold a letter to open an app", "Assign in edit mode", true),
    )),
    Page("system_screen", "System", R.drawable.set_system, "QuickStep, System UI module", listOf(
        Header("Open / close animations (QuickStep)"),
        Action("qs_status", "Status"),
        Action("qs_install", "Install QuickStep module", "Needs root. Reboot afterwards"),
        Action("qs_remove", "Remove QuickStep module"),
        Header("System UI module (LSPosed)"),
        Action("sysui_status", "Status"),
        Action("sysui_restart", "Restart System UI", "After turning the module on or updating"),
        Header("Troubleshooting"),
        Switch("debug_mode", "Debug mode",
            "Detailed logs for Diagnostics (animation timings, unlock steps, key timing). Off: only start-up and errors are logged", false),
    )),
    Action("qs_diag", "Diagnostics", "Report to copy and send"),
)

private val sp: SharedPreferences get() = LauncherApp.get().prefs.sp

private fun enabled(dep: String?): Boolean {
    if (dep == null) return true
    val invert = dep.startsWith("!")
    val k = dep.removePrefix("!")
    return sp.getBoolean(k, parents[k] ?: false) != invert
}

@Composable
private fun SettingsRoot(version: Int, open: String?, pickFont: () -> Unit, refresh: () -> Unit) {
    val start = remember { sections.filterIsInstance<Page>().firstOrNull { it.key == open } }
    var page by remember { mutableStateOf(start) }
    val act = LocalContext.current as Activity
    Bb10PushHost(
        pushed = page,
        content = {
            Bb10Scaffold(title = "JL10 Settings", subtitle = "BB10 launcher " + versionName(act)) {
                Bb10LazyColumn(Modifier.fillMaxSize()) {
                    items(sections) { s ->
                        when (s) {
                            is Page -> SectionRow(s, version) { page = s }
                            is Action -> SectionRow(s.title, s.summary, R.drawable.set_diag) { SettingsTools.click(act, s.key, refresh) }
                            else -> {}
                        }
                    }
                }
            }
        },
        page = { p ->
            PageScreen(p, version, onBack = { page = null }, pickFont = pickFont, refresh = refresh,
                goto = { key -> page = sections.filterIsInstance<Page>().firstOrNull { it.key == key } })
        },
    )
}

@Composable
private fun SectionRow(p: Page, version: Int, onClick: () -> Unit) {
    val act = LocalContext.current as Activity
    val sub = remember(version) { SettingsTools.status(act, p.key) } ?: p.summary
    SectionRow(p.title, sub, p.icon, onClick)
}

@Composable
private fun SectionRow(title: String, sub: String?, icon: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(icon) { iconBitmap(ctx, icon) }
    Bb10ListItem(
        title = title,
        subtitle = sub,
        leading = { if (bmp != null) Image(bmp, null, Modifier.size(34.dp)) },
        onClick = onClick,
    )
}

private fun iconBitmap(c: Context, res: Int): ImageBitmap? = runCatching {
    val d = if (res == 0) c.packageManager.getApplicationIcon(c.packageName) else c.getDrawable(res)!!
    val px = (34 * c.resources.displayMetrics.density).toInt().coerceAtLeast(24)
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, px, px)
    d.draw(Canvas(b))
    b.asImageBitmap()
}.getOrNull()

private fun versionName(c: Context): String =
    runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: ""

@Composable
private fun PageScreen(
    p: Page, version: Int, onBack: () -> Unit, pickFont: () -> Unit, refresh: () -> Unit, goto: (String) -> Unit,
) {
    var sub by remember { mutableStateOf<Multi?>(null) }
    Bb10PushHost(
        pushed = sub,
        content = {
            Bb10Scaffold(title = p.title, onBack = onBack) {
                Bb10LazyColumn(Modifier.fillMaxSize()) {
                    items(p.items) { item ->
                        ItemView(item, version, pickFont, refresh, goto) { sub = it }
                    }
                }
            }
        },
        page = { m -> MultiScreen(m, onBack = { sub = null; refresh() }) },
    )
}

@Composable
private fun ItemView(
    item: Item, version: Int, pickFont: () -> Unit, refresh: () -> Unit, goto: (String) -> Unit, openMulti: (Multi) -> Unit,
) {
    val act = LocalContext.current as Activity
    when (item) {
        is Header -> Bb10ListHeader(item.title)
        is DisplaySize -> {
            Bb10ScalePicker(rememberJl10Scale())
            Bb10Text(
                "For JL10's Settings, the Hub and every BB10 app",
                style = Bb10.typography.caption, color = Bb10.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        is SizePicker -> {
            val state = remember(item.key) {
                duc1607.bb10.designsystem.theme.Bb10ScaleState.shared(LauncherApp.get().prefs.scale(item.key)) { v ->
                    sp.edit().putString(item.key, String.format(java.util.Locale.US, "%.2f", v)).apply()
                }
            }
            Bb10ScalePicker(state, label = item.title, steps = SIZE_STEPS)
            Bb10Text(
                item.summary,
                style = Bb10.typography.caption, color = Bb10.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        is Switch -> {
            val on = remember(version) { sp.getBoolean(item.key, item.default) }
            Bb10Toggle(
                checked = on,
                onCheckedChange = { sp.edit().putBoolean(item.key, it).apply(); refresh() },
                label = item.title,
                subtitle = item.summary,
                enabled = enabled(item.dependsOn),
            )
        }
        is Choice -> {
            val opts = remember(item) { item.options(act) }
            val value = remember(version) { sp.getString(item.key, item.default) ?: item.default }
            Bb10Dropdown(
                label = item.title,
                options = opts.map { it.first },
                selectedIndex = opts.indexOfFirst { it.second == value }.coerceAtLeast(0),
                onSelect = { i -> sp.edit().putString(item.key, opts[i].second).apply(); refresh() },
                enabled = enabled(item.dependsOn),
            )
        }
        is NumberEntry -> {
            var editing by remember { mutableStateOf(false) }
            val value = remember(version) { sp.getString(item.key, item.default) ?: item.default }
            Bb10LinkItem(
                title = item.title,
                subtitle = if (value == "0" || value.isEmpty()) "Off" else "$value characters",
                enabled = enabled(item.dependsOn),
            ) { editing = true }
            if (editing) {
                var text by remember { mutableStateOf(value) }
                Bb10InputDialog(
                    title = item.title, value = text, onValueChange = { s -> text = s.filter { it.isDigit() }.take(3) },
                    message = item.summary,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    onConfirm = { sp.edit().putString(item.key, text.ifEmpty { "0" }).apply(); editing = false; refresh() },
                    onDismiss = { editing = false },
                )
            }
        }
        is TextEntry -> {
            var editing by remember { mutableStateOf(false) }
            val value = remember(version) { sp.getString(item.key, item.default) ?: item.default }
            Bb10LinkItem(title = item.title, subtitle = value.ifEmpty { "None" }) { editing = true }
            if (editing) {
                var text by remember { mutableStateOf(value) }
                Bb10InputDialog(
                    title = item.title, value = text, onValueChange = { text = it },
                    message = item.summary,
                    onConfirm = { sp.edit().putString(item.key, text.trim()).apply(); editing = false; refresh() },
                    onDismiss = { editing = false },
                )
            }
        }
        is Multi -> {
            val n = remember(version) { sp.getStringSet(item.key, emptySet())?.size ?: 0 }
            Bb10LinkItem(title = item.title, subtitle = if (n == 0) item.summary else "$n selected") { openMulti(item) }
        }
        is Action -> {
            val status = remember(version) {
                if (item.key == "caldav_accounts") com.bb10.launcher.caldav.CalDavAccounts.list(act).let { l ->
                    if (l.isEmpty()) null else l.joinToString(", ") { it.name }
                } else if (item.key == "mail_accounts") com.bb10.launcher.mail.MailAccounts.list(act).let { l ->
                    if (l.isEmpty()) null else l.joinToString(", ") { it.email }
                } else SettingsTools.status(act, item.key)
            }
            Bb10LinkItem(title = item.title, subtitle = status ?: item.summary) {
                when (item.key) {
                    "font_pick" -> pickFont()
                    "goto_system" -> goto("system_screen")
                    "mail_accounts" -> act.startActivity(com.bb10.launcher.mail.MailActivity.accounts(act))
                    "caldav_accounts" -> act.startActivity(com.bb10.launcher.caldav.CalDavActivity.open(act))
                    else -> SettingsTools.click(act, item.key, refresh)
                }
            }
        }
        is Page -> {}
    }
}

@Composable
private fun MultiScreen(m: Multi, onBack: () -> Unit) {
    val apps = remember {
        val app = LauncherApp.get()
        app.apps.ensureLoaded()
        val all = app.apps.all().sortedBy { it.label().lowercase() }
        if (m.byPackage) all.distinctBy { it.pkg }.map { it.label() to it.pkg } else all.map { it.label() to it.key }
    }
    var chosen by remember { mutableStateOf(sp.getStringSet(m.key, emptySet())?.toSet() ?: emptySet()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.first.contains(query, ignoreCase = true) }
    Bb10Scaffold(
        title = m.title,
        subtitle = "${chosen.size} selected",
        onBack = onBack,
        search = if (searching) Bb10Search(query, { query = it }, { searching = false; query = "" }) else null,
        actions = if (searching) emptyList() else listOf(
            duc1607.bb10.designsystem.components.Bb10Action("search", "Search", duc1607.bb10.designsystem.icons.Bb10Icons.Search,
                androidx.compose.ui.input.key.Key.S, primary = true) { searching = true },
        ),
    ) {
        Bb10LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.second }) { (label, id) ->
                Bb10Checkbox(
                    checked = id in chosen,
                    onCheckedChange = { on ->
                        chosen = if (on) chosen + id else chosen - id
                        sp.edit().putStringSet(m.key, HashSet(chosen)).apply()
                    },
                    label = label,
                )
            }
        }
    }
}
