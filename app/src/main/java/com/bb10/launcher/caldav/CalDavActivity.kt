package com.bb10.launcher.caldav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bb10.kit.Jl10SystemBars
import com.bb10.kit.Jl10Theme
import com.bb10.launcher.LauncherApp
import com.bb10.launcher.ui.Ui
import duc1607.bb10.designsystem.components.Bb10Action
import duc1607.bb10.designsystem.components.Bb10Dialog
import duc1607.bb10.designsystem.components.Bb10LazyColumn
import duc1607.bb10.designsystem.components.Bb10ListItem
import duc1607.bb10.designsystem.components.Bb10PushHost
import duc1607.bb10.designsystem.components.Bb10Scaffold
import duc1607.bb10.designsystem.components.Bb10Text
import duc1607.bb10.designsystem.components.Bb10TextField
import duc1607.bb10.designsystem.components.Bb10TitleAction
import duc1607.bb10.designsystem.components.bb10VerticalScroll
import duc1607.bb10.designsystem.icons.Bb10Icons
import duc1607.bb10.designsystem.theme.Bb10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class CalDavActivity : ComponentActivity() {
    companion object {
        fun open(c: Context): Intent = Intent(c, CalDavActivity::class.java)
    }

    private var version by mutableIntStateOf(0)
    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        LauncherApp.get().ensureInit()
        Ui.init(this)
        CalDav.init(this)
        val need = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ask.launch(need.toTypedArray())
        setContent {
            Jl10Theme {
                Jl10SystemBars()
                Accounts(version) { finish() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        version++
    }
}

@Composable
private fun Accounts(version: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var local by remember { mutableIntStateOf(0) }
    val accounts = remember(version, local) { CalDavAccounts.list(ctx) }
    val syncing by CalDav.syncing.collectAsState()
    var adding by remember(accounts.isEmpty()) { mutableStateOf(accounts.isEmpty() && version <= 1) }
    var picked by remember { mutableStateOf<CalDavAccount?>(null) }
    Bb10PushHost(
        pushed = if (adding) Unit else null,
        content = {
            Bb10Scaffold(
                title = "Calendar accounts",
                subtitle = "CalDAV, in BB10 Calendar",
                onBack = onBack,
                actions = listOf(
                    Bb10Action("add", "Add", Bb10Icons.Add, Key.A, primary = true) { adding = true },
                    Bb10Action("sync", "Sync now", Bb10Icons.Refresh, Key.R) { CalDav.syncAll { local++ } },
                ),
            ) {
                Bb10LazyColumn(Modifier.fillMaxSize()) {
                    if (accounts.isEmpty()) item {
                        Bb10Text("No calendar accounts yet. Add one and its calendars show up in BB10 Calendar, " +
                            "the Hub's agenda and the Active Frames, in sync both ways.",
                            style = Bb10.typography.secondary, color = Bb10.colors.textSecondary, modifier = Modifier.padding(16.dp))
                    }
                    items(accounts, key = { it.id }) { a ->
                        Bb10ListItem(
                            title = a.name,
                            subtitle = if (a.id in syncing) "Syncing…" else CalDavAccounts.status(ctx, a.id).ifEmpty { a.url },
                            leading = { Box(Modifier.size(14.dp).background(Color(0xFF00A8DF))) },
                            onClick = { picked = a },
                        )
                    }
                }
            }
        },
        page = { AddAccount(onBack = { adding = false }) { adding = false; local++ } },
    )
    picked?.let { a ->
        Bb10Dialog(
            title = a.name,
            message = "Sync it now, or remove it? (Removing takes its calendars off the phone; the server keeps them.)",
            confirmText = "Remove", onConfirm = {
                picked = null
                Thread { CalDav.removeLocal(a); CalDavAccounts.remove(ctx, a.id); CalDav.schedule() }.start()
                local++
            },
            dismissText = "Close", onDismiss = { picked = null },
            neutralText = "Sync", onNeutral = { picked = null; CalDav.sync(a) },
        )
    }
}

@Composable
private fun AddAccount(onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = url.isNotBlank() && user.isNotBlank() && password.isNotEmpty() && !busy
    fun signIn() {
        if (!valid) return
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { CalDav.discover(url, user.trim(), password) } }
            busy = false
            result.onSuccess { cals ->
                val host = runCatching { java.net.URI(if (url.contains("://")) url.trim() else "https://" + url.trim()).host }.getOrNull() ?: url
                val a = CalDavAccount(UUID.randomUUID().toString(), url.trim(), user.trim(), "${user.trim()} ($host)")
                CalDavAccounts.save(ctx, a, password)
                CalDav.schedule()
                CalDav.sync(a)
                android.widget.Toast.makeText(ctx, "Found ${cals.size} calendar" + (if (cals.size == 1) "" else "s") + ": syncing", android.widget.Toast.LENGTH_SHORT).show()
                onDone()
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }
    Bb10Scaffold(
        title = "Add calendar account",
        dismissAction = Bb10TitleAction("Cancel") { onBack() },
        acceptAction = Bb10TitleAction(if (busy) "Signing in…" else "Sign in", enabled = valid) { signIn() },
    ) {
        Column(Modifier.fillMaxSize().bb10VerticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Bb10TextField(url, { url = it.trim() }, label = "Server", required = true, placeholder = "cloud.example.com",
                helper = "Your Nextcloud, iCloud (caldav.icloud.com), Fastmail, Radicale... address. Google isn't supported (it needs Google sign-in).",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), startEditing = true)
            Bb10TextField(user, { user = it }, label = "Username", required = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            Bb10TextField(password, { password = it }, label = "Password", required = true, password = true,
                helper = "iCloud and Fastmail need an app password, made in the account's settings", onDone = { signIn() })
            error?.let {
                Bb10Text(it, color = Bb10.colors.error, style = Bb10.typography.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}
