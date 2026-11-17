package com.bb10.launcher.mail

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.bb10.kit.Jl10SystemBars
import com.bb10.kit.Jl10Theme
import com.bb10.launcher.LauncherApp
import com.bb10.launcher.ui.Ui
import duc1607.bb10.designsystem.components.Bb10Action
import duc1607.bb10.designsystem.components.Bb10Button
import duc1607.bb10.designsystem.components.Bb10Dialog
import duc1607.bb10.designsystem.components.Bb10Divider
import duc1607.bb10.designsystem.components.Bb10Dropdown
import duc1607.bb10.designsystem.components.Bb10Icon
import duc1607.bb10.designsystem.components.Bb10InputDialog
import duc1607.bb10.designsystem.components.Bb10LazyColumn
import duc1607.bb10.designsystem.components.Bb10ListHeader
import duc1607.bb10.designsystem.components.Bb10ListItem
import duc1607.bb10.designsystem.components.Bb10PushHost
import duc1607.bb10.designsystem.components.Bb10Scaffold
import duc1607.bb10.designsystem.components.Bb10Text
import duc1607.bb10.designsystem.components.Bb10TextField
import duc1607.bb10.designsystem.components.Bb10TitleAction
import duc1607.bb10.designsystem.components.Bb10Toggle
import duc1607.bb10.designsystem.components.bb10VerticalScroll
import duc1607.bb10.designsystem.icons.Bb10Icons
import duc1607.bb10.designsystem.theme.Bb10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MailActivity : ComponentActivity() {
    companion object {
        fun accounts(c: Context): Intent = Intent(c, MailActivity::class.java).putExtra("screen", "accounts")

        fun view(c: Context, hubId: String): Intent = Intent(c, MailActivity::class.java).putExtra("screen", "view").putExtra("id", hubId)

        fun compose(c: Context, mode: String? = null, hubId: String? = null, acct: String? = null): Intent =
            Intent(c, MailActivity::class.java).putExtra("screen", "compose").putExtra("mode", mode)
                .putExtra("id", hubId).putExtra("acct", acct)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        LauncherApp.get().ensureInit()
        Ui.init(this)
        Mail.init(this)
        val screen = intent.getStringExtra("screen") ?: "accounts"
        val id = intent.getStringExtra("id")
        setContent {
            Jl10Theme {
                Jl10SystemBars()
                when {
                    screen == "view" && id != null -> MessageScreen(id, onBack = { finish() })
                    screen == "compose" -> ComposeScreen(intent.getStringExtra("mode"), id, intent.getStringExtra("acct")) { finish() }
                    else -> AccountsScreen { finish() }
                }
            }
        }
    }
}

@Composable
private fun AccountsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val accounts = remember(version) { MailAccounts.list(ctx) }
    val syncing by Mail.syncing.collectAsState()
    val errors by Mail.errors.collectAsState()
    var adding by remember { mutableStateOf(accounts.isEmpty()) }
    var picked by remember { mutableStateOf<MailAccount?>(null) }
    var signing by remember { mutableStateOf<String?>(null) }
    Bb10PushHost(
        pushed = if (adding) Unit else null,
        content = {
            Bb10Scaffold(
                title = "Email accounts",
                subtitle = "In the BlackBerry Hub",
                onBack = onBack,
                actions = listOf(
                    Bb10Action("add", "Add", Bb10Icons.Add, Key.A, primary = true) { adding = true },
                    Bb10Action("sync", "Sync now", Bb10Icons.Refresh, Key.R) { Mail.syncAll() },
                    Bb10Action("signature", "Signature", Bb10Icons.Edit, Key.G) { signing = Mail.signature() },
                ),
            ) {
                Bb10LazyColumn(Modifier.fillMaxSize()) {
                    if (accounts.isEmpty()) item {
                        Bb10Text("No email accounts yet. Add one and its inbox shows in the Hub with everything else.",
                            style = Bb10.typography.secondary, color = Bb10.colors.textSecondary,
                            modifier = Modifier.padding(16.dp))
                    }
                    items(accounts, key = { it.id }) { a ->
                        Bb10ListItem(
                            title = a.email,
                            subtitle = when {
                                a.id in syncing -> "Syncing…"
                                errors[a.id] != null -> errors[a.id]
                                else -> "${a.imapHost} · up to date"
                            },
                            leading = { Box(Modifier.size(14.dp).background(Color(a.color))) },
                            onClick = { picked = a },
                        )
                    }
                }
            }
        },
        page = {
            AddAccountScreen(onBack = { adding = false }) {
                adding = false
                version++
            }
        },
    )
    signing?.let { s ->
        Bb10InputDialog(
            title = "Signature", value = s, onValueChange = { signing = it },
            message = "Added under every email you write. Leave it empty for none.",
            onConfirm = { Mail.setSignature(s); signing = null },
            onDismiss = { signing = null },
        )
    }
    picked?.let { a ->
        Bb10Dialog(
            title = a.email,
            message = "Sync it now, or remove it from the Hub? (Removing only forgets it on this phone.)",
            confirmText = "Remove", onConfirm = {
                MailAccounts.remove(ctx, a.id)
                Mail.removed(a)
                picked = null
                version++
            },
            dismissText = "Close", onDismiss = { picked = null },
            neutralText = "Sync", onNeutral = { Mail.sync(a); picked = null },
        )
    }
}

@Composable
private fun AddAccountScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var imapHost by remember { mutableStateOf("") }
    var imapPort by remember { mutableStateOf("993") }
    var imapSec by remember { mutableStateOf(Security.SSL) }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("465") }
    var smtpSec by remember { mutableStateOf(Security.SSL) }
    var user by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = email.contains('@') && email.substringAfter('@').contains('.') && password.isNotEmpty()
    val note = remember(email) { MailConfig.note(email) }

    fun signIn() {
        if (!valid || busy != null) return
        error = null
        scope.launch {
            busy = "Finding your provider's settings…"
            if (!advanced || imapHost.isBlank()) {
                val cfg = withContext(Dispatchers.IO) { MailConfig.find(email.trim()) }
                imapHost = cfg.imapHost; imapPort = cfg.imapPort.toString(); imapSec = cfg.imapSec
                smtpHost = cfg.smtpHost; smtpPort = cfg.smtpPort.toString(); smtpSec = cfg.smtpSec
                if (user.isBlank()) user = if (cfg.userIsLocalPart) email.trim().substringBefore('@') else email.trim()
            }
            val n = MailAccounts.list(ctx).size
            val acct = MailAccount(
                email.trim(), name.trim(), imapHost.trim(), imapPort.toIntOrNull() ?: 993, imapSec,
                smtpHost.trim(), smtpPort.toIntOrNull() ?: 465, smtpSec, user.ifBlank { email }.trim(),
                MailAccount.COLORS[n % MailAccount.COLORS.size],
            )
            busy = "Signing in…"
            val err = withContext(Dispatchers.IO) { Mail.test(acct, password) }
            busy = null
            if (err == null) {
                MailAccounts.save(ctx, acct, password)
                Mail.added(acct)
                Toast.makeText(ctx, "Added: its inbox is coming into the Hub", Toast.LENGTH_LONG).show()
                onDone()
            } else {
                error = err
                advanced = true
            }
        }
    }

    Bb10Scaffold(
        title = "Add email account",
        onBack = onBack,
        acceptAction = Bb10TitleAction("Sign in", enabled = valid && busy == null) { signIn() },
    ) {
        Column(Modifier.fillMaxSize().bb10VerticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Bb10TextField(email, { email = it.trim().lowercase() }, label = "Email address", required = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            Bb10TextField(password, { password = it }, label = "Password", required = true, password = true,
                helper = note ?: "Gmail, Outlook, Yahoo and iCloud need an app password, made in the account's security settings")
            Bb10TextField(name, { name = it }, label = "Your name", helper = "Shown to the people you email")
            if (busy != null) Bb10Text(busy!!, style = Bb10.typography.secondary, color = Bb10.colors.accent,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (error != null) Bb10Text(error!!, style = Bb10.typography.secondary, color = Bb10.colors.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Bb10Button("Sign in", { signIn() }, primary = true, enabled = valid && busy == null)
            Bb10Toggle(advanced, { advanced = it }, label = "Advanced", subtitle = "Set the servers yourself")
            if (advanced) {
                Bb10ListHeader("Incoming (IMAP)")
                Bb10TextField(imapHost, { imapHost = it.trim() }, label = "Server", placeholder = "imap.example.com")
                Bb10TextField(imapPort, { imapPort = it.filter(Char::isDigit) }, label = "Port",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                SecurityPicker("Security", imapSec) { imapSec = it }
                Bb10TextField(user, { user = it.trim() }, label = "User name", placeholder = "Usually your email address")
                Bb10ListHeader("Outgoing (SMTP)")
                Bb10TextField(smtpHost, { smtpHost = it.trim() }, label = "Server", placeholder = "smtp.example.com")
                Bb10TextField(smtpPort, { smtpPort = it.filter(Char::isDigit) }, label = "Port",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                SecurityPicker("Security", smtpSec) { smtpSec = it }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SecurityPicker(label: String, value: Security, onPick: (Security) -> Unit) {
    val names = listOf("SSL / TLS", "STARTTLS", "None (not safe)")
    Bb10Dropdown(label, names, value.ordinal, { onPick(Security.entries[it]) })
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MessageScreen(
    id: String,
    onBack: () -> Unit,
    onCompose: ((mode: String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val write: (String) -> Unit = onCompose ?: { m -> ctx.startActivity(MailActivity.compose(ctx, m, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val parsed = remember { MailMsg.parse(id) }
    val acct = remember { parsed?.let { MailAccounts.byId(ctx, it.first) } }
    val msg = remember { parsed?.let { Mail.message(it.first, it.second) } }
    if (parsed == null || acct == null || msg == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(ctx, "This message isn't on the phone any more", Toast.LENGTH_SHORT).show()
            onBack()
        }
        return
    }
    val hub = LauncherApp.get().hub
    var body by remember { mutableStateOf<MailBody?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pictures by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        settled = true
    }
    LaunchedEffect(id) {
        hub.byId(id)?.let { if (!it.read) hub.setRead(it, true) }
        body = withContext(Dispatchers.IO) { Mail.body(acct, parsed.second) }
        loading = false
    }
    val remote = body?.html?.contains(Regex("(?i)<img[^>]+src=[\"']?https?:")) == true
    Bb10Scaffold(
        title = com.bb10.launcher.hub.HtmlText.line(msg.subject).ifBlank { "(no subject)" },
        subtitle = acct.email,
        onBack = onBack,
        maxVisibleActions = 4,
        actions = listOf(
            Bb10Action("reply", "Reply", MailIcons.Reply, Key.R, primary = true) {
                write("reply")
            },
            Bb10Action("replyall", "Reply All", MailIcons.ReplyAll, Key.L) {
                write("replyall")
            },
            Bb10Action("forward", "Forward", MailIcons.Forward, Key.F) {
                write("forward")
            },
            Bb10Action("unread", "Mark Unread", Bb10Icons.Mail, Key.U) {
                hub.byId(id)?.let { hub.setRead(it, false) }
                onBack()
            },
            Bb10Action("delete", "Delete", Bb10Icons.Delete, Key.Delete) { confirmDelete = true },
        ),
    ) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            var headShown by remember { mutableStateOf(true) }
            var travel by remember { mutableStateOf(0) }
            var quietUntil by remember { mutableStateOf(0L) }
            fun setHead(shown: Boolean) {
                if (shown == headShown) return
                headShown = shown
                travel = 0
                quietUntil = android.os.SystemClock.uptimeMillis() + 250
            }
            val onBodyScroll: (Int, Int) -> Unit = { dy, y ->
                if (dy != 0 && android.os.SystemClock.uptimeMillis() >= quietUntil) {
                    travel = if ((dy > 0) == (travel > 0)) travel + dy else dy
                    when {
                        dy < 0 && y <= 0 -> setHead(true)
                        travel > 60 && y > 60 -> setHead(false)
                        travel < -60 -> setHead(true)
                    }
                }
            }
            var headH by remember { mutableStateOf(0) }
            val hide by androidx.compose.animation.core.animateFloatAsState(if (headShown) 0f else 1f, tween(220), label = "head")
            Column(Modifier.fillMaxWidth().zIndex(1f).onSizeChanged { headH = it.height }
                .offset { IntOffset(0, -(headH * hide).roundToInt()) }.background(Bb10.colors.surface)) {
            Row(Modifier.fillMaxWidth().background(Bb10.colors.surface).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(acct.color)), contentAlignment = Alignment.Center) {
                    Bb10Text(msg.sender.take(1).uppercase(), style = Bb10.typography.title, color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Bb10Text(msg.sender, style = Bb10.typography.body, maxLines = 1)
                    Bb10Text(msg.fromAddr, style = Bb10.typography.caption, color = Bb10.colors.textSecondary, maxLines = 1)
                    Bb10Text("To: " + msg.to + if (msg.cc.isNotBlank()) "   Cc: " + msg.cc else "",
                        style = Bb10.typography.caption, color = Bb10.colors.textSecondary, maxLines = 2)
                }
                Bb10Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(msg.date)),
                    style = Bb10.typography.caption, color = Bb10.colors.textSecondary, textAlign = TextAlign.End)
            }
            Bb10Divider()
            val atts = body?.attachments.orEmpty()
            if (atts.isNotEmpty()) {
                Bb10ListHeader("Attachments", trailing = atts.size.toString())
                atts.forEach { att ->
                    Bb10ListItem(
                        title = att.name,
                        subtitle = sizeText(att.size) + " · " + att.type,
                        leading = { Bb10Icon(MailIcons.Attach) },
                        onClick = {
                            Toast.makeText(ctx, "Opening ${att.name}…", Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { Mail.attachment(acct, parsed.second, att) }
                                if (f == null) Toast.makeText(ctx, "Couldn't download it", Toast.LENGTH_SHORT).show()
                                else runCatching {
                                    val uri = FileProvider.getUriForFile(ctx, "com.bb10.launcher.files", f)
                                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, att.type)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), att.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        },
                    )
                }
            }
            if (remote && !pictures) {
                Row(Modifier.fillMaxWidth().background(Bb10.colors.surfaceVariant)
                    .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { pictures = true }.padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Bb10Text("Pictures hidden", style = Bb10.typography.caption,
                        color = Bb10.colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                    Bb10Text("Show", style = Bb10.typography.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = Bb10.colors.accent)
                }
            }
            }
            val headDp = with(androidx.compose.ui.platform.LocalDensity.current) { headH.toDp() }
            Column(Modifier.fillMaxSize()) {
            when {
                loading || !settled -> Bb10Text("Loading…", color = Bb10.colors.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = headDp + 16.dp))
                body == null -> Bb10Text("Couldn't load this message. Check the connection and open it again.",
                    color = Bb10.colors.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = headDp + 16.dp))
                body!!.html == null -> {
                    val ss = rememberScrollState()
                    LaunchedEffect(ss) {
                        var last = 0
                        androidx.compose.runtime.snapshotFlow { ss.value }.collect { y -> onBodyScroll(y - last, y); last = y }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().bb10VerticalScroll(ss)) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Bb10Text(body!!.text ?: "", style = Bb10.typography.body,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = headDp + 16.dp))
                    }
                    }
                }
                else -> MailBodyView(body!!, pictures, Bb10.colors.isDark,
                    Modifier.weight(1f).fillMaxWidth().clipToBounds(), onBodyScroll, topGap = headDp.value.roundToInt())
            }
            }
        }
    }
    if (confirmDelete) Bb10Dialog(
        title = "Delete this email?", message = "It goes to the account's Trash.",
        confirmText = "Delete", onConfirm = {
            hub.byId(id)?.let { hub.delete(it) }
            confirmDelete = false
            onBack()
        }, dismissText = "Cancel", onDismiss = { confirmDelete = false },
    )
}

private fun sizeText(b: Int): String = when {
    b <= 0 -> "?"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> String.format(java.util.Locale.getDefault(), "%.1f MB", b / 1048576f)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MailBodyView(
    b: MailBody, pictures: Boolean, dark: Boolean, modifier: Modifier, onScroll: (dy: Int, y: Int) -> Unit = { _, _ -> },
    topGap: Int = 0,
) {
    val scrolled by androidx.compose.runtime.rememberUpdatedState(onScroll)
    val html0 = remember(b, dark) {
        if (b.html != null) {
            if (b.html.contains("name=\"viewport\"", true))
                b.html.replace(Regex("(?i)(user-scalable\\s*=\\s*(no|0)|maximum-scale\\s*=\\s*[0-9.]+|minimum-scale\\s*=\\s*[0-9.]+)"), "")
            else "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" + b.html
        } else {
            val esc = android.text.TextUtils.htmlEncode(b.text ?: "")
                .replace(Regex("(https?://[^\\s<]+)"), "<a href=\"$1\">$1</a>")
            val fg = if (dark) "#f0f0f0" else "#1e1e1e"
            val bg = if (dark) "#000000" else "#ffffff"
            "<html><body style=\"background:$bg;color:$fg;font-family:sans-serif;font-size:15px;white-space:pre-wrap;word-wrap:break-word;margin:14px\">$esc</body></html>"
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var zoom by remember { mutableStateOf(1f) }
    var zoomFixes by remember { mutableStateOf(0) }
    val onZoom by androidx.compose.runtime.rememberUpdatedState { z: Float ->
        if (zoomFixes < 2 && kotlin.math.abs(z - zoom) > 0.05f) {
            zoom = z
            zoomFixes++
        }
    }
    val gapCss = if (topGap <= 0) 0 else ((topGap + 12) / zoom.coerceIn(0.2f, 1f)).roundToInt()
    val html = remember(html0, gapCss) {
        if (gapCss <= 0) html0
        else {
            val gap = "<div style=\"height:${gapCss}px;margin:0;padding:0\"></div>"
            val m = Regex("(?i)<body[^>]*>").find(html0)
            if (m != null) html0.substring(0, m.range.last + 1) + gap + html0.substring(m.range.last + 1) else gap + html0
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).apply {
                setOnScrollChangeListener { _, _, y, _, oy -> scrolled(y - oy, y) }
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = b.html != null
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(if (b.html != null || !dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String?) {
                        for (d in longArrayOf(60, 400, 1200)) v.postDelayed({ v.zoomBy(0.02f) }, d)
                    }

                    override fun onScaleChanged(v: WebView, oldScale: Float, newScale: Float) {
                        onZoom((newScale / density).coerceAtMost(1f))
                    }

                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, r.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
            }
        },
        update = { w ->
            w.settings.blockNetworkImage = !pictures
            w.settings.loadsImagesAutomatically = true
            if (w.tag != html to pictures) {
                w.tag = html to pictures
                w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

@Composable
internal fun ComposeScreen(mode: String?, refId: String?, acctHint: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val accounts = remember { MailAccounts.list(ctx) }
    if (accounts.isEmpty()) {
        LaunchedEffect(Unit) {
            Toast.makeText(ctx, "Add an email account first", Toast.LENGTH_SHORT).show()
            ctx.startActivity(MailActivity.accounts(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onBack()
        }
        return
    }
    val ref = remember { refId?.let { MailMsg.parse(it) }?.let { Mail.message(it.first, it.second) } }
    var from by remember {
        mutableStateOf(accounts.indexOfFirst { it.id == (ref?.acct ?: acctHint) }.coerceAtLeast(0))
    }
    val me = accounts[from].email.lowercase()
    var to by remember {
        mutableStateOf(when (mode) {
            "reply", "replyall" -> ref?.replyTo?.ifBlank { null } ?: ref?.let { "${it.sender} <${it.fromAddr}>" } ?: ""
            else -> ""
        })
    }
    var cc by remember {
        mutableStateOf(if (mode == "replyall" && ref != null)
            (ref.to.split(',') + ref.cc.split(',')).map { it.trim() }.filter { it.isNotEmpty() && !it.lowercase().contains(me) }.joinToString(", ")
        else "")
    }
    var subject by remember {
        mutableStateOf(when (mode) {
            "reply", "replyall" -> Mail.prefixed("Re:", com.bb10.launcher.hub.HtmlText.line(ref?.subject ?: ""))
            "forward" -> Mail.prefixed("Fwd:", com.bb10.launcher.hub.HtmlText.line(ref?.subject ?: ""))
            else -> ""
        })
    }
    var body by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf(Mail.signature()) }
    var quoted by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (ref == null || mode == null) return@LaunchedEffect
        val a = accounts.firstOrNull { it.id == ref.acct } ?: return@LaunchedEffect
        val b = withContext(Dispatchers.IO) { Mail.body(a, ref.uid) }
        val text = b?.text ?: b?.html?.let { Mail.stripHtml(it) } ?: ref.snippet
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ref.date))
        quoted = if (mode == "forward")
            "\n\n---------- Forwarded message ----------\nFrom: ${ref.sender} <${ref.fromAddr}>\nDate: $date\nSubject: ${com.bb10.launcher.hub.HtmlText.line(ref.subject)}\nTo: ${ref.to}\n\n$text"
        else "\n\nOn $date, ${ref.sender} wrote:\n" + text.lines().joinToString("\n") { "> $it" }
    }
    val canSend = to.contains('@') && !sending
    fun send() {
        if (!canSend) return
        sending = true
        error = null
        val signed = if (signature.isBlank()) body else body.trimEnd() + "\n\n" + signature.trim()
        Mail.send(accounts[from], Draft(to, cc, "", subject, signed + quoted,
            inReplyTo = if (mode == "reply" || mode == "replyall") ref?.msgId ?: "" else "", refs = ref?.refs ?: "")) { err ->
            sending = false
            if (err == null) {
                Toast.makeText(ctx, "Sent", Toast.LENGTH_SHORT).show()
                onBack()
            } else error = err
        }
    }
    Bb10Scaffold(
        title = when (mode) {
            "reply" -> "Reply"
            "replyall" -> "Reply All"
            "forward" -> "Forward"
            else -> "New Email"
        },
        dismissAction = Bb10TitleAction("Cancel") { onBack() },
        acceptAction = Bb10TitleAction(if (sending) "Sending…" else "Send", enabled = canSend) { send() },
    ) {
        Column(Modifier.fillMaxSize().bb10VerticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            if (accounts.size > 1) Bb10Dropdown("From", accounts.map { it.email }, from, { from = it })
            Bb10TextField(to, { to = it }, label = "To", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                startEditing = mode == null || mode == "forward")
            Bb10TextField(cc, { cc = it }, label = "Cc", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            Bb10TextField(subject, { subject = it }, label = "Subject")
            Bb10TextField(body, { body = it }, label = "Message", singleLine = false, minLines = 8,
                startEditing = mode == "reply" || mode == "replyall")
            Bb10TextField(signature, { signature = it }, label = "Signature", singleLine = false, clearable = true)
            if (error != null) Bb10Text("Not sent: " + error, color = Bb10.colors.error, style = Bb10.typography.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (quoted.isNotEmpty()) Bb10Text(quoted.trim(), style = Bb10.typography.caption, color = Bb10.colors.textSecondary,
                modifier = Modifier.padding(16.dp))
        }
    }
}

object MailIcons {
    private fun stroked(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, pathBuilder = block)
        }.build()

    val Reply = stroked("reply") {
        moveTo(9f, 6f); lineTo(4f, 11f); lineTo(9f, 16f)
        moveTo(4f, 11f); lineTo(14f, 11f); quadTo(20f, 11f, 20f, 18f)
    }
    val ReplyAll = stroked("replyAll") {
        moveTo(8f, 6f); lineTo(3f, 11f); lineTo(8f, 16f)
        moveTo(12f, 6f); lineTo(7f, 11f); lineTo(12f, 16f)
        moveTo(7f, 11f); lineTo(15f, 11f); quadTo(21f, 11f, 21f, 18f)
    }
    val Forward = stroked("forward") {
        moveTo(15f, 6f); lineTo(20f, 11f); lineTo(15f, 16f)
        moveTo(20f, 11f); lineTo(10f, 11f); quadTo(4f, 11f, 4f, 18f)
    }
    val Attach = stroked("attach") {
        moveTo(16.5f, 7f); lineTo(16.5f, 16f)
        arcTo(4.5f, 4.5f, 0f, false, true, 7.5f, 16f)
        lineTo(7.5f, 6f)
        arcTo(3f, 3f, 0f, false, true, 13.5f, 6f)
        lineTo(13.5f, 15.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 10.5f, 15.5f)
        lineTo(10.5f, 8f)
    }
}
