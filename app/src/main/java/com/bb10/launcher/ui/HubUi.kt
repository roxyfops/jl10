package com.bb10.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bb10.kit.Jl10Theme
import com.bb10.kit.KitComposeView
import com.bb10.launcher.LauncherApp
import com.bb10.launcher.Prefs
import com.bb10.launcher.R
import com.bb10.launcher.hub.HubActions
import com.bb10.launcher.hub.HubItem
import com.bb10.launcher.hub.HubListenerService
import com.bb10.launcher.hub.HubStore
import com.bb10.launcher.mail.Mail
import com.bb10.launcher.mail.MailAccounts
import com.bb10.launcher.mail.MailActivity
import com.bb10.launcher.mail.ComposeScreen
import com.bb10.launcher.mail.MessageScreen
import duc1607.bb10.designsystem.components.Bb10PushHost
import duc1607.bb10.designsystem.components.Bb10Action
import duc1607.bb10.designsystem.components.Bb10Divider
import duc1607.bb10.designsystem.components.Bb10OnHighlight
import duc1607.bb10.designsystem.components.Bb10Search
import duc1607.bb10.designsystem.components.Bb10StandaloneActionBar
import duc1607.bb10.designsystem.components.Bb10Text
import duc1607.bb10.designsystem.components.Bb10TextField
import duc1607.bb10.designsystem.components.Bb10TitleBar
import duc1607.bb10.designsystem.components.bb10Clickable
import duc1607.bb10.designsystem.components.rememberBb10Highlight
import duc1607.bb10.designsystem.icons.Bb10Icons
import duc1607.bb10.designsystem.theme.Bb10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

class HubUi(private val ctx: Context, private val host: HubPage.Host, internal val ownBar: Boolean) {
    companion object {
        private const val OVERLAY_TAB = "hub_overlay_tab"
        const val PRIORITY = "\u0000priority"
        const val ATTACH = "\u0000attach"
    }

    internal var selecting by mutableStateOf(false)
    internal var foldersOpen by mutableStateOf(false)
    internal var folderQuery by mutableStateOf("")
    internal var barFolded by mutableStateOf(false)

    private val hub: HubStore get() = LauncherApp.get().hub

    internal var filter by mutableStateOf<String?>(if (ownBar) LauncherApp.get().prefs.sp.getString(OVERLAY_TAB, null) else null)
    internal var query by mutableStateOf("")
    internal var searching by mutableStateOf(false)
    internal var accountsOpen by mutableStateOf(false)
    internal var replyTarget by mutableStateOf<HubItem?>(null)
    internal var replyText by mutableStateOf("")
    internal var agendaOpen by mutableStateOf(false)
    internal var version by mutableIntStateOf(0)
    internal var focusedId: String? = null
    internal var focusFirst by mutableIntStateOf(0)
    internal val listState = LazyListState()

    internal sealed interface MailPage {
        data class View(val id: String) : MailPage
        data class Write(val mode: String?, val id: String?, val acct: String?) : MailPage
    }

    internal data class MailEntry(val level: Int, val page: MailPage)

    internal var mailStack by mutableStateOf<List<MailPage>>(emptyList())

    internal fun pushMail(p: MailPage) {
        if (mailStack.lastOrNull() == p) return
        mailStack = mailStack + p
    }

    internal fun popMail(level: Int) {
        mailStack = mailStack.take(level)
    }

    fun closeMail() {
        if (mailStack.isNotEmpty() && mailStack.none { it is MailPage.Write }) mailStack = emptyList()
    }
    internal var scope: CoroutineScope? = null

    private val dark: Boolean
        get() = "dark" == LauncherApp.get().prefs.sp.getString(Prefs.HUB_THEME, "bright")

    val listView = KitComposeView(ctx).apply {
        setContent { Jl10Theme(dark = dark) { HubContent(this@HubUi) } }
    }
    val barView = KitComposeView(ctx).apply {
        setContent { Jl10Theme(dark = dark) { HubBarLayer(this@HubUi) } }
    }

    init {
        listView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> barView.post { setLayerFull(true) } }
    }

    internal fun setLayerFull(full: Boolean) {
        val lp = barView.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val h = if (full) android.view.ViewGroup.LayoutParams.MATCH_PARENT else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        val top = if (full) IntArray(2).also { listView.getLocationInWindow(it) }[1] else 0
        if (lp.height == h && lp.topMargin == top) return
        lp.height = h
        lp.topMargin = top
        barView.layoutParams = lp
    }

    fun refresh() {
        version++
    }

    internal sealed interface Row {
        data class Header(val label: String, val dayEnd: Long, val unread: Boolean = false) : Row
        data class Item(val it: HubItem) : Row
    }

    internal fun shown(): List<HubItem> = when (filter) {
        PRIORITY -> hub.items(null, query).filter { hub.isPriority(it) }
        ATTACH -> hub.items(null, query).filter { HubStore.isMail(it) && Mail.hasAttachment(it.id) }
        else -> hub.items(filter, query)
    }

    internal fun rows(): List<Row> {
        val out = ArrayList<Row>()
        val all = shown().distinctBy { it.id }.sortedByDescending { it.time }
        var lastDay = -1L
        for (it in all) {
            val day = dayOf(it.time)
            if (day != lastDay) {
                out += Row.Header(DateFormat.format("EEEE, MMMM d, yyyy", Date(it.time)).toString(), endOfDay(it.time))
                lastDay = day
            }
            out += Row.Item(it)
        }
        return out
    }

    internal fun accounts(): List<String> {
        val g = hub.groups()
        val mail = g.filter { HubStore.isMailPkg(it) }
        val apps = g.filter { !HubStore.isMailPkg(it) && it != com.bb10.launcher.hub.HubKinds.CALLS && it != com.bb10.launcher.hub.HubKinds.NOTIF }
        val calls = if (com.bb10.launcher.hub.CallLogHub.readable(ctx) || com.bb10.launcher.hub.HubKinds.CALLS in g)
            listOf(com.bb10.launcher.hub.HubKinds.CALLS) else emptyList()
        return listOf(com.bb10.launcher.hub.HubKinds.NOTIF) + mail + apps + calls
    }

    internal fun title(): String = when (filter) {
        null -> "BlackBerry Hub"
        PRIORITY -> "Priority Hub"
        ATTACH -> "Attachments"
        else -> LauncherApp.get().apps.labelForPackage(filter)
    }

    internal fun subtitle(): String {
        val prio = filter == PRIORITY
        val unread = if (prio) hub.unreadPriority() else hub.unread(filter)
        val what = when {
            prio -> "Filtered by Priority"
            filter == null -> "All accounts"
            HubStore.isMailPkg(filter) -> "Email"
            else -> "Account"
        }
        return if (unread > 0) "$what · $unread unread" else what
    }

    private fun dayOf(t: Long): Long = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000L + it.get(Calendar.DAY_OF_YEAR) }

    private fun endOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    private val bitmaps = HashMap<String, ImageBitmap?>()
    private val accents = HashMap<String, Int>()

    internal fun icon(pkg: String): ImageBitmap? = bitmaps.getOrPut(pkg) { host.iconForPackage(pkg)?.let { bitmapOf(it) } }

    private val pictures = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 200
    }

    internal fun picture(it: HubItem): ImageBitmap? {
        pictures[it.id]?.let { return it }
        val d = runCatching { hub.avatar(it)?.loadDrawable(ctx) }.getOrNull() ?: return null
        return bitmapOf(d)?.also { b -> pictures[it.id] = b }
    }

    internal fun glyph(res: Int): ImageBitmap? = bitmaps.getOrPut("res:$res") { ctx.getDrawable(res)?.let { bitmapOf(it) } }

    internal fun callGlyph(it: HubItem): ImageBitmap? = glyph(when {
        it.text.startsWith("Outgoing") -> R.drawable.ic_call_out
        it.text.startsWith("Missed") || it.text.startsWith("Declined") || it.text.startsWith("Blocked") -> R.drawable.ic_call_missed
        else -> R.drawable.ic_call_in
    })

    private val monos = HashMap<String, Pair<ImageBitmap?, Boolean>>()

    internal fun mono(key: String): Pair<ImageBitmap?, Boolean> = monos.getOrPut(key) {
        val res = when {
            key == com.bb10.launcher.hub.HubKinds.NOTIF -> R.drawable.ic_bell
            key == com.bb10.launcher.hub.HubKinds.CALLS -> R.drawable.ic_phone
            HubStore.isMailPkg(key) -> R.drawable.ic_mail
            else -> 0
        }
        if (res != 0) return@getOrPut (ctx.getDrawable(res)?.let { fitted(drawnBig(it)) } ?: glyph(res)) to true
        themedIcon(key)?.let { return@getOrPut it to true }
        runCatching { hub.smallIcon(key)?.loadDrawable(ctx)?.mutate()?.let { d -> fitted(drawnBig(d)) } }.getOrNull()?.let { return@getOrPut it to true }
        icon(key) to false
    }

    private fun themedIcon(pkg: String): ImageBitmap? {
        if (android.os.Build.VERSION.SDK_INT < 33) return null
        return runCatching { themedIcon33(pkg) }.getOrNull()
    }

    @androidx.annotation.RequiresApi(33)
    private fun themedIcon33(pkg: String): ImageBitmap? {
        val pm = ctx.packageManager
        val launch = pm.getLaunchIntentForPackage(pkg)
        val d = launch?.let { pm.resolveActivity(it, 0)?.activityInfo?.loadIcon(pm) } ?: pm.getApplicationIcon(pkg)
        val m = (d as? android.graphics.drawable.AdaptiveIconDrawable)?.monochrome?.mutate() ?: return null
        val s = 192
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        m.setBounds(0, 0, s, s)
        m.setTint(android.graphics.Color.WHITE)
        m.draw(Canvas(b))
        return fitted(b)
    }

    private fun fitted(src: Bitmap): ImageBitmap? {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var btm = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 24) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > btm) btm = y
        }
        if (r < l || btm < t) return null
        val cw = r - l + 1
        val ch = btm - t + 1
        val out = 96
        val pad = 4
        val k = (out - 2 * pad).toFloat() / maxOf(cw, ch)
        val dst = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
        val dw = cw * k
        val dh = ch * k
        Canvas(dst).drawBitmap(src, android.graphics.Rect(l, t, r + 1, btm + 1),
            android.graphics.RectF((out - dw) / 2, (out - dh) / 2, (out + dw) / 2, (out + dh) / 2),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return dst.asImageBitmap()
    }

    private fun drawnBig(d: Drawable): Bitmap {
        val s = 192
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(b))
        return b
    }

    private fun bitmapOf(d: Drawable): ImageBitmap? = runCatching {
        val s = 96
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val was = d.copyBounds()
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(b))
        d.bounds = was
        b.asImageBitmap()
    }.getOrNull()

    internal fun accent(pkg: String): Int = accents.getOrPut(pkg) {
        if (HubStore.isMailPkg(pkg)) return@getOrPut MailAccounts.byPkg(ctx, pkg)?.color ?: Ui.BLUE
        var col = Ui.BLUE
        runCatching {
            val d = host.iconForPackage(pkg) ?: return@runCatching
            val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val was = d.copyBounds()
            d.setBounds(0, 0, 24, 24)
            d.draw(Canvas(b))
            d.bounds = was
            val hsv = FloatArray(3)
            var x = 0.0; var y = 0.0; var w = 0.0; var v = 0.0
            for (py in 0 until 24) for (px in 0 until 24) {
                val p = b.getPixel(px, py)
                if (android.graphics.Color.alpha(p) < 200) continue
                android.graphics.Color.colorToHSV(p, hsv)
                if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue
                val wt = (hsv[1] * hsv[2]).toDouble()
                x += Math.cos(Math.toRadians(hsv[0].toDouble())) * wt
                y += Math.sin(Math.toRadians(hsv[0].toDouble())) * wt
                v += hsv[2] * wt
                w += wt
            }
            if (w > 3) {
                val hue = ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
                col = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.75f, maxOf(0.65, v / w).toFloat()))
            }
        }
        col
    }

    internal fun open(it: HubItem, from: android.graphics.Rect?) {
        if (HubStore.isMail(it)) {
            pushMail(MailPage.View(it.id))
            return
        }
        HubActions.open(ctx, hub, it, from)
        host.onHubItemOpened()
    }

    fun open(it: HubItem) = open(it, null)

    private fun glyphD(res: Int) = Ui.glyph(ctx, res, Ui.TEXT)

    internal fun showActions(it: HubItem) {
        val m = ArrayList<ActionMenu.Item>()
        m += ActionMenu.Item(glyphD(R.drawable.ic_open), "Open") { open(it) }
        val n = hub.live(it)
        val reply = HubActions.replyAction(n)
        val number = if (HubStore.isCall(it)) com.bb10.launcher.hub.CallLogHub.number(ctx, it) else null
        if (!number.isNullOrEmpty()) {
            val tel = android.net.Uri.fromParts("tel", number, null)
            m += ActionMenu.Item(glyphD(R.drawable.ic_phone), "Call") {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_CALL, tel).newTask()) }
                    .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, tel).newTask()) } }
                hub.setRead(it, true)
            }
            m += ActionMenu.Item(glyphD(R.drawable.ic_text), "Send Text") {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null)).newTask()) }
            }
        }
        if (HubStore.isMail(it)) {
            m += ActionMenu.Item(glyphD(R.drawable.ic_reply), "Reply") { pushMail(MailPage.Write("reply", it.id, null)) }
            m += ActionMenu.Item(glyphD(R.drawable.ic_reply), "Reply All") { pushMail(MailPage.Write("replyall", it.id, null)) }
            m += ActionMenu.Item(glyphD(R.drawable.ic_send), "Forward") { pushMail(MailPage.Write("forward", it.id, null)) }
        } else if (reply != null) {
            m += ActionMenu.Item(glyphD(R.drawable.ic_reply), "Reply") { startReply(it) }
        }
        n?.actions?.forEach { ac ->
            if (ac === reply || ac.actionIntent == null) return@forEach
            if ((ac.remoteInputs?.size ?: 0) > 0) return@forEach
            m += ActionMenu.Item(glyphD(R.drawable.ic_done), ac.title.toString()) {
                HubActions.send(ctx, ac.actionIntent, null)
                hub.setRead(it, true)
            }
        }
        m += if (it.read) ActionMenu.Item(glyphD(R.drawable.ic_unread), "Mark Unread") { hub.setRead(it, false) }
        else ActionMenu.Item(glyphD(R.drawable.ic_read), "Mark Read") { hub.setRead(it, true) }
        m += ActionMenu.Item(glyphD(R.drawable.ic_doneall), "Mark Prior Read") { hub.markPriorRead(it) }
        val who = HubStore.senderOf(it)
        val prefs = LauncherApp.get().prefs
        val vipWho = prefs.set(Prefs.VIP_SENDERS).contains(who)
        val vipApp = prefs.set(Prefs.VIP_APPS).contains(it.pkg)
        if (who.isNotEmpty()) m += ActionMenu.Item(glyphD(R.drawable.ic_priority),
            (if (vipWho) "Remove " else "Add ") + who + (if (vipWho) " from" else " to") + " Priority Hub") {
            hub.setPriority(Prefs.VIP_SENDERS, who, !vipWho)
        }
        val appName = LauncherApp.get().apps.labelForPackage(it.pkg)
        m += ActionMenu.Item(glyphD(R.drawable.ic_priority),
            (if (vipApp) "Remove all of " else "Add all of ") + appName + (if (vipApp) " from" else " to") + " Priority Hub") {
            hub.setPriority(Prefs.VIP_APPS, it.pkg, !vipApp)
        }
        m += ActionMenu.Item(glyphD(R.drawable.ic_delete), "Delete") { hub.delete(it) }
        ActionMenu.show(host.overlays(), it.title, host.iconForPackage(it.pkg), m)
    }

    private fun android.content.Intent.newTask() = addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    fun showMenu() {
        val m = ArrayList<ActionMenu.Item>()
        m += ActionMenu.Item(glyphD(R.drawable.ic_to_top), "To Top", "T") { scope?.launch { listState.scrollToItem(0) } }
        m += ActionMenu.Item(glyphD(R.drawable.ic_to_bottom), "To Bottom", "B") {
            scope?.launch { listState.scrollToItem(maxOf(0, rows().size - 1)) }
        }
        m += ActionMenu.Item(glyphD(R.drawable.ic_attach), "Attachments") { pick(if (filter == ATTACH) null else ATTACH) }
        m += ActionMenu.Item(glyphD(R.drawable.ic_compose), "Compose", "C") { compose() }
        m += ActionMenu.Item(glyphD(R.drawable.ic_search), "Search", "S") { showSearch() }
        m += ActionMenu.Item(glyphD(R.drawable.ic_settings), "Settings") { host.openSettings() }
        m += ActionMenu.Item(glyphD(R.drawable.ic_reset), "Refresh") {
            refresh()
            if (Mail.accounts().isNotEmpty()) {
                Mail.syncAll()
                Toast.makeText(ctx, "Checking email…", Toast.LENGTH_SHORT).show()
            }
        }
        m += ActionMenu.Item(glyphD(R.drawable.ic_doneall), "Select More") { selecting = true }
        m += ActionMenu.Item(glyphD(R.drawable.ic_read), "Mark All Read") { markAllRead() }
        m += ActionMenu.Item(glyphD(R.drawable.ic_delete), "Clear All") { confirmClearAll() }
        m += ActionMenu.Item(glyphD(R.drawable.ic_mail), "Email Accounts") { ctx.startActivity(MailActivity.accounts(ctx).newTask()) }
        ActionMenu.show(host.overlays(), null, null, m)
    }

    internal fun compose() {
        if (Mail.accounts().isNotEmpty()) {
            val acct = filter?.takeIf { HubStore.isMailPkg(it) }?.removePrefix(HubStore.MAIL)
            pushMail(MailPage.Write(null, null, acct))
        } else host.openCompose()
    }

    internal fun markAllRead() {
        if (filter == ATTACH) hub.markRead(shown()) else hub.markAllRead(if (filter == PRIORITY) null else filter, filter == PRIORITY)
    }

    internal fun confirmMarkAllRead() {
        val m = ArrayList<ActionMenu.Item>()
        m += ActionMenu.Item(glyphD(R.drawable.ic_doneall), "Mark All Read") { markAllRead() }
        m += ActionMenu.Item(glyphD(R.drawable.ic_delete), "Clear All") { confirmClearAll() }
        m += ActionMenu.Item(glyphD(R.drawable.ic_close), "Cancel", null)
        ActionMenu.show(host.overlays(), title(), null, m)
    }

    private fun confirmClearAll() {
        val m = ArrayList<ActionMenu.Item>()
        val mail = rows().filterIsInstance<Row.Item>().any { com.bb10.launcher.hub.HubStore.isMail(it.it) }
        m += ActionMenu.Item(glyphD(R.drawable.ic_delete), if (mail) "Delete everything (emails to the server's Trash)" else "Delete everything") {
            if (filter == ATTACH) hub.delete(shown()) else hub.clearAll(if (filter == PRIORITY) null else filter, filter == PRIORITY)
        }
        m += ActionMenu.Item(glyphD(R.drawable.ic_close), "Cancel", null)
        ActionMenu.show(host.overlays(), "Clear " + title() + "?", null, m)
    }

    internal fun dayActions(d: Row.Header) {
        val all = rows().filterIsInstance<Row.Item>().map { it.it }
        if (d.unread) {
            val un = all.filter { !it.read }
            if (un.isEmpty()) return
            val m = ArrayList<ActionMenu.Item>()
            m += ActionMenu.Item(glyphD(R.drawable.ic_doneall), "Mark All Read") { hub.markRead(un) }
            m += ActionMenu.Item(glyphD(R.drawable.ic_delete), "Delete Unread") {
                confirmDelete("Delete all unread?", un)
            }
            ActionMenu.show(host.overlays(), "Unread", null, m)
            return
        }
        val prior = all.filter { it.time <= d.dayEnd }
        if (prior.isEmpty()) return
        val m = ArrayList<ActionMenu.Item>()
        m += ActionMenu.Item(glyphD(R.drawable.ic_doneall), "Mark Prior Read") { hub.markRead(prior) }
        m += ActionMenu.Item(glyphD(R.drawable.ic_delete), "Delete Prior") {
            confirmDelete("Delete everything from ${d.label} and earlier?", prior)
        }
        ActionMenu.show(host.overlays(), "${d.label} and earlier", null, m)
    }

    private fun confirmDelete(q: String, list: List<HubItem>) {
        val m = ArrayList<ActionMenu.Item>()
        m += ActionMenu.Item(glyphD(R.drawable.ic_delete), "Delete ${list.size} items") { hub.delete(list) }
        m += ActionMenu.Item(glyphD(R.drawable.ic_close), "Cancel", null)
        ActionMenu.show(host.overlays(), q, null, m)
    }

    internal fun startReply(it: HubItem) {
        replyTarget = it
        replyText = ""
    }

    internal fun sendReply() {
        val t = replyTarget ?: return
        if (replyText.isBlank()) return
        if (HubActions.reply(ctx, hub.live(t), replyText)) {
            hub.setRead(t, true)
            Toast.makeText(ctx, "Sent", Toast.LENGTH_SHORT).show()
            replyTarget = null
        } else {
            Toast.makeText(ctx, "This message can no longer be replied to here", Toast.LENGTH_SHORT).show()
        }
    }

    fun showSearch() {
        searching = true
    }

    fun openAccounts() {
        accountsPeek = false
        accountsOpen = true
    }

    internal val push = androidx.compose.animation.core.Animatable(0f)
    internal var navWidthPx = 1f

    fun accountsPulled(): Float = push.value * navWidthPx

    internal var accountsPeek by mutableStateOf(false)
    internal var peekWidthPx = 1f
    private var lastPullPx = 0f
    private var pullDir = 0f

    private var pullFromClosed = true

    internal var settlingTo = -1f

    fun dragAccounts(px: Float) {
        if (lastPullPx == 0f && pullDir == 0f) pullFromClosed = push.value <= 0.001f
        if (px != lastPullPx) pullDir = px - lastPullPx
        lastPullPx = px
        scope?.launch { push.snapTo((px / navWidthPx).coerceIn(0f, 1f)) }
    }

    fun releaseAccounts(px: Float, velocity: Float) {
        val v = px.coerceIn(0f, navWidthPx)
        val dir = if (kotlin.math.abs(velocity) > 150f) velocity else 0f
        val dp = ctx.resources.displayMetrics.density
        val to = duc1607.bb10.designsystem.components.navRest(v, navWidthPx, peekWidthPx, dir, pullFromClosed,
            if (dir == 0f) 0f else velocity / dp)
        lastPullPx = 0f
        pullDir = 0f
        pullFromClosed = true
        accountsPeek = to == peekWidthPx
        accountsOpen = to > 0f
        val d = to - v
        val toward = if (dir == 0f) 0f else if (d > 0f) velocity else -velocity
        val ms = duc1607.bb10.designsystem.components.navSettleMs(kotlin.math.abs(d), toward)
        settlingTo = to / navWidthPx
        scope?.launch {
            push.animateTo(to / navWidthPx, tween(ms, easing = duc1607.bb10.designsystem.components.NavSettleEasing))
        }
    }

    internal fun pick(f: String?) {
        filter = f
        if (ownBar) LauncherApp.get().prefs.sp.edit().putString(OVERLAY_TAB, f).apply()
        accountsOpen = false
        foldersOpen = false
        folderQuery = ""
        scope?.launch { listState.scrollToItem(0) }
        if (f != null && HubStore.isMailPkg(f)) MailAccounts.byPkg(ctx, f)?.let { Mail.sync(it) }
    }

    fun onBack(): Boolean = when {
        mailStack.isNotEmpty() -> {
            if (!listView.handleBack()) popMail(mailStack.size - 1)
            true
        }
        accountsOpen -> { accountsOpen = false; true }
        foldersOpen -> { foldersOpen = false; true }
        selecting -> { selecting = false; true }
        replyTarget != null -> { replyTarget = null; true }
        searching -> { searching = false; query = ""; true }
        agendaOpen -> { agendaOpen = false; true }
        filter != null -> { pick(null); true }
        else -> false
    }

    private fun focused(): HubItem? = focusedId?.let { id -> shown().firstOrNull { it.id == id } }

    fun onKey(code: Int, e: KeyEvent): Boolean {
        if (mailStack.isNotEmpty()) return false
        if (searching || replyTarget != null) return false
        if (accountsOpen) {
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                accountsOpen = false
                return true
            }
            return false
        }
        val rows = rows()
        val s = scope
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                listView.requestFocus()
                focusFirst++
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { openAccounts(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> return false
            KeyEvent.KEYCODE_T -> { s?.launch { listState.scrollToItem(0) }; return true }
            KeyEvent.KEYCODE_B -> { s?.launch { listState.scrollToItem(maxOf(0, rows.size - 1)) }; return true }
            KeyEvent.KEYCODE_SPACE -> {
                s?.launch { listState.animateScrollBy(listView.height * (if (e.isShiftPressed) -0.85f else 0.85f)) }
                return true
            }
            KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_P -> {
                val dir = if (code == KeyEvent.KEYCODE_N) 1 else -1
                val start = listState.firstVisibleItemIndex
                var i = start + dir
                while (i in rows.indices && rows[i] !is Row.Header) i += dir
                val target = if (i in rows.indices) i else if (dir > 0) rows.size - 1 else 0
                s?.launch { listState.scrollToItem(maxOf(0, target)) }
                return true
            }
            KeyEvent.KEYCODE_S -> { showSearch(); return true }
            KeyEvent.KEYCODE_C -> { compose(); return true }
            KeyEvent.KEYCODE_R -> {
                val it = focused() ?: return true
                when {
                    HubStore.isMail(it) -> pushMail(MailPage.Write("reply", it.id, null))
                    HubActions.replyAction(hub.live(it)) != null -> startReply(it)
                    else -> open(it)
                }
                return true
            }
            KeyEvent.KEYCODE_U -> { focused()?.let { hub.setRead(it, !it.read) }; return true }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                val it = focused() ?: return true
                val m = ArrayList<ActionMenu.Item>()
                m += ActionMenu.Item(glyphD(R.drawable.ic_delete), "Delete") { hub.delete(it) }
                m += ActionMenu.Item(glyphD(R.drawable.ic_close), "Cancel", null)
                ActionMenu.show(host.overlays(), "Delete “${it.title}”?", host.iconForPackage(it.pkg), m)
                return true
            }
        }
        return false
    }

    internal fun accessGranted(): Boolean = HubListenerService.isGranted(ctx)

    internal fun requestAccess() = host.requestNotificationAccess()
}

private val PriorityIcon: ImageVector = ImageVector.Builder("priority", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(12f, 20f); lineTo(12f, 5f)
        moveTo(6.5f, 10.5f); lineTo(12f, 5f); lineTo(17.5f, 10.5f)
    }
}.build()

private val AttachIcon: ImageVector = ImageVector.Builder("attach", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f) {
        moveTo(14f, 21f); lineTo(5.5f, 21f); lineTo(5.5f, 2.5f); lineTo(13f, 2.5f); lineTo(17f, 6.5f); lineTo(17f, 11f)
        moveTo(13f, 2.5f); lineTo(13f, 6.5f); lineTo(17f, 6.5f)
    }
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.3f) {
        moveTo(16f, 12.5f); lineTo(16f, 20f)
        curveTo(16f, 21.4f, 17f, 22.3f, 18.1f, 22.3f); curveTo(19.2f, 22.3f, 20.2f, 21.4f, 20.2f, 20f)
        lineTo(20.2f, 13.5f); curveTo(20.2f, 12.7f, 19.6f, 12.1f, 18.9f, 12.1f); curveTo(18.2f, 12.1f, 17.6f, 12.7f, 17.6f, 13.5f)
        lineTo(17.6f, 19.5f)
    }
}.build()

private val SelectIcon: ImageVector = ImageVector.Builder("select", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f) {
        moveTo(2.5f, 6f); lineTo(21.5f, 6f)
        moveTo(2.5f, 11f); lineTo(21.5f, 11f)
        moveTo(2.5f, 16f); lineTo(11.5f, 16f)
        moveTo(13.5f, 16.5f); lineTo(16.5f, 19.5f); lineTo(22f, 13.5f)
    }
}.build()

@Composable
private fun bb(px: Float): androidx.compose.ui.unit.Dp {
    val w = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.widthPixels
    return with(androidx.compose.ui.platform.LocalDensity.current) { (px * w / 720f).toDp() }
}

@Composable
private fun bbSp(px: Float): androidx.compose.ui.unit.TextUnit {
    val w = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.widthPixels
    val d = androidx.compose.ui.platform.LocalDensity.current
    return androidx.compose.ui.unit.TextUnit(px * 1.12f * w / 720f / (d.density * d.fontScale), androidx.compose.ui.unit.TextUnitType.Sp)
}

private fun isEmoji(cp: Int) = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B05..0x2B55 || cp in 0x231A..0x23FF ||
    cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x200D || cp == 0xFE0F || cp == 0x20E3

internal fun smallEmoji(s: String, k: Float = 0.72f): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (isEmoji(cp) && cp != 0x200D && cp != 0xFE0F) {
                var j = i + n
                while (j < s.length && isEmoji(s.codePointAt(j))) j += Character.charCount(s.codePointAt(j))
                pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = androidx.compose.ui.unit.TextUnit(k, androidx.compose.ui.unit.TextUnitType.Em)))
                append(s, i, j)
                pop()
                i = j
            } else {
                append(s, i, i + n)
                i += n
            }
        }
    }

private fun androidx.compose.ui.text.TextStyle.tight() =
    copy(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))

private object HubLook {
    @Composable fun row() = if (Bb10.colors.isDark) Bb10.colors.surface else Color.White
    @Composable fun header() = if (Bb10.colors.isDark) Bb10.colors.surfaceVariant else Color(0xFFF0F0F0)
    @Composable fun rule() = if (Bb10.colors.isDark) Bb10.colors.divider else Color(0xFFE6E6E6)
    @Composable fun dayRule() = if (Bb10.colors.isDark) Bb10.colors.divider else Color(0xFFCBCBCB)
}

private const val BAR = 0.85f

@Composable
private fun HubBar(ui: HubUi) {
    val att = ui.filter == HubUi.ATTACH
    val fold = androidx.compose.animation.core.animateFloatAsState(if (ui.barFolded) 1f else 0f, tween(180), label = "fold")
    Bb10StandaloneActionBar(
        collapse = fold.value,
        actions = listOf(
            Bb10Action("attach", if (att) "All" else "Attachments", AttachIcon) { ui.pick(if (att) null else HubUi.ATTACH) },
            Bb10Action("compose", "Compose", Bb10Icons.Edit, Key.C, primary = true) { ui.compose() },
            Bb10Action("search", "Search", Bb10Icons.Search, Key.S) { ui.showSearch() },
            Bb10Action("settings", "Settings", Bb10Icons.Sliders) { ui.showMenu() },
            Bb10Action("markall", "Mark All Read", Bb10Icons.Check) { ui.markAllRead() },
        ),
        leading = Bb10Action("accounts", "Accounts", Bb10Icons.Menu) { if (ui.accountsOpen) ui.accountsOpen = false else ui.openAccounts() },
        maxVisibleActions = 4,
        onMore = { ui.showMenu() },
        metrics = duc1607.bb10.designsystem.components.bb10BarMetrics(BAR),

    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HubContent(ui: HubUi) {
    val c = Bb10.colors
    ui.scope = rememberCoroutineScope()
    val version = ui.version
    val rows = remember(version, ui.filter, ui.query) { ui.rows() }
    if (ui.searching) BackHandler { ui.searching = false; ui.query = "" }
    LaunchedEffect(ui.listState) {
        var li = 0
        var lo = 0
        androidx.compose.runtime.snapshotFlow { ui.listState.firstVisibleItemIndex to ui.listState.firstVisibleItemScrollOffset }
            .collect { (i, o) ->
                val down = i > li || (i == li && o > lo + 6)
                val up = i < li || (i == li && o < lo - 6)
                when {
                    i == 0 && o == 0 -> ui.barFolded = false
                    down -> ui.barFolded = true
                    up -> ui.barFolded = false
                }
                if (down || up) { li = i; lo = o }
            }
    }
    if (ui.replyTarget != null) BackHandler { ui.replyTarget = null }

    val pullToAgenda = Modifier.pointerInput(Unit) {
        val open = 56.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val atTop = !ui.listState.canScrollBackward
            var dy = 0f
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                dy += ch.positionChangeIgnoreConsumed().y
                if (atTop && !ui.agendaOpen && dy > open) ui.agendaOpen = true
                else if (ui.agendaOpen && dy < -open) ui.agendaOpen = false
            }
        }
    }
    Bb10PushHost(pushed = if (ui.ownBar) ui.mailStack.firstOrNull()?.let { HubUi.MailEntry(0, it) } else null, content = {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val navW = drawerWidth(maxWidth)
    val density = androidx.compose.ui.platform.LocalDensity.current
    ui.navWidthPx = with(density) { navW.toPx() }
    ui.peekWidthPx = with(density) { PEEK.toPx() }
    LaunchedEffect(ui.accountsOpen, ui.accountsPeek) {
        val to = if (!ui.accountsOpen) 0f else if (ui.accountsPeek) ui.peekWidthPx / ui.navWidthPx else 1f
        if (ui.settlingTo == to || (ui.push.isRunning && ui.push.targetValue == to)) {
            ui.settlingTo = -1f
            return@LaunchedEffect
        }
        ui.push.animateTo(to, tween(200))
    }
    val pushed by remember { androidx.compose.runtime.derivedStateOf { ui.push.value > 0f } }
    if (pushed) {
        AccountsDrawer(ui, version, navW)
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((navW.toPx() * ui.push.value - 9.dp.toPx()).toInt(), 0) }.width(9.dp).fillMaxHeight()
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color(0x40000000), Color(0x99000000)))))
    }
    Box(Modifier.fillMaxSize().offset { androidx.compose.ui.unit.IntOffset((navW.toPx() * ui.push.value).toInt(), 0) }.background(HubLook.row())) {
        Column(Modifier.fillMaxSize()) {
            if (ui.searching) {
                Bb10TitleBar(title = "Hub", search = Bb10Search(ui.query, { ui.query = it }, { ui.searching = false; ui.query = "" }, placeholder = "Search Hub"))
            } else {
                HubHeader(ui, version)
            }
            AnimatedVisibility(ui.foldersOpen, enter = expandVertically(tween(180)), exit = shrinkVertically(tween(150))) { FolderDrop(ui, version) }
            AnimatedVisibility(ui.agendaOpen, enter = expandVertically(), exit = shrinkVertically()) { Agenda(ui) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize().then(pullToAgenda),
                    state = ui.listState,
                    contentPadding = PaddingValues(bottom = bb(109f * BAR + 8f)),
                ) {
                    val firstItem = rows.firstOrNull { it is HubUi.Row.Item }
                    rows.forEach { r ->
                        when (r) {
                            is HubUi.Row.Header -> stickyHeader(key = "h:" + r.label) { DayHeader(ui, r) }
                            is HubUi.Row.Item -> item(key = "i:" + r.it.id) { HubRow(ui, r.it, version, focus = r == firstItem) }
                        }
                    }
                }
                if (rows.isEmpty()) EmptyHub(ui)
            }
            ui.replyTarget?.let { ReplyBar(ui, it) }
        }
        if (ui.ownBar) Box(Modifier.align(Alignment.BottomCenter)) { HubBar(ui) }
        DrawerScrim(ui, pushed)
    }
    }
    }, page = { e -> MailPageView(ui, e) })
}

private val PEEK = 64.dp

private fun drawerWidth(screen: androidx.compose.ui.unit.Dp) = minOf(screen * 0.76f, 320.dp)

@Composable
private fun DrawerScrim(ui: HubUi, pushed: Boolean) {
    if (ui.accountsOpen) BackHandler { ui.accountsOpen = false }
    if (!pushed) return
    val c = Bb10.colors
    Box(Modifier.fillMaxSize().drawBehind { drawRect(c.scrim.copy(alpha = c.scrim.alpha * ui.push.value)) }
        .then(if (ui.accountsOpen) Modifier.clickable(remember { MutableInteractionSource() }, null) { ui.accountsOpen = false } else Modifier))
}

@Composable
private fun AccountsDrawer(ui: HubUi, version: Int, width: androidx.compose.ui.unit.Dp) {
    val hub = LauncherApp.get().hub
    val accounts = remember(version) { ui.accounts() }
    Box(Modifier.width(width).fillMaxHeight().background(Color.Black)) {
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        DrawerRow(ui.glyph(R.drawable.ic_hub), true, "BlackBerry Hub", hub.unread(null), ui.filter == null) { ui.pick(null) }
        DrawerRow(ui.glyph(R.drawable.ic_priority), true, "Priority Hub", hub.unreadPriority(), ui.filter == HubUi.PRIORITY) { ui.pick(HubUi.PRIORITY) }
        accounts.forEach { p ->
            val (ic, tint) = ui.mono(p)
            DrawerRow(ic, tint, LauncherApp.get().apps.labelForPackage(p), hub.unread(p), ui.filter == p) { ui.pick(p) }
        }
        DrawerRow(ui.glyph(R.drawable.ic_mail), true, "Email Accounts", 0, false) {
            ui.accountsOpen = false
            val ctx = ui.listView.context
            ctx.startActivity(MailActivity.accounts(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    }
}

@Composable
private fun DrawerRow(icon: ImageBitmap?, glyph: Boolean, label: String, unread: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Bb10.colors
    val hl = rememberBb10Highlight()
    val fg = Color(0xFFF2F2F2)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(if (selected) c.accent else Color.Transparent)
                .bb10Clickable(highlight = hl, flat = true, onClick = onClick).padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Bb10OnHighlight(hl) {
                Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val s = if (unread > 0) 30.dp else 34.dp
                    if (icon != null) Image(icon, null, Modifier.size(s),
                        colorFilter = if (glyph) ColorFilter.tint(if (selected || hl.active) Color.White else Color(0xFFD0D0D0)) else null)
                    else Spacer(Modifier.size(s))
                    if (unread > 0) Bb10Text(unread.toString(), style = Bb10.typography.caption.tight()
                        .copy(fontSize = androidx.compose.ui.unit.TextUnit(11.5f, androidx.compose.ui.unit.TextUnitType.Sp)),
                        color = if (selected) Color.White else Color(0xFFC8C8C8), maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                Bb10Text(label, style = Bb10.typography.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(18.5f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161616)))
    }
}

@Composable
private fun HubBarLayer(ui: HubUi) {
    val open = ui.mailStack.isNotEmpty()
    androidx.compose.runtime.SideEffect { ui.setLayerFull(true) }
    LaunchedEffect(open) { ui.setLayerFull(true) }
    Bb10PushHost(pushed = ui.mailStack.firstOrNull()?.let { HubUi.MailEntry(0, it) }, content = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val navW = drawerWidth(maxWidth)
            val scrim = Bb10.colors.scrim
            Box(Modifier.align(Alignment.BottomCenter).offset { androidx.compose.ui.unit.IntOffset((navW.toPx() * ui.push.value).toInt(), 0) }
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val push = ui.push.value
                    if (push > 0f) drawRect(scrim.copy(alpha = scrim.alpha * push), blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                }) {
                HubBar(ui)
                if (ui.accountsOpen) Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null) { ui.accountsOpen = false })
            }
        }
    }, page = { e -> MailPageView(ui, e) })
}
@Composable
private fun MailPageView(ui: HubUi, e: HubUi.MailEntry) {
    val next = ui.mailStack.getOrNull(e.level + 1)?.let { HubUi.MailEntry(e.level + 1, it) }
    Bb10PushHost(pushed = next, content = {
        when (val p = e.page) {
            is HubUi.MailPage.View -> MessageScreen(p.id, onBack = { ui.popMail(e.level) },
                onCompose = { m -> ui.pushMail(HubUi.MailPage.Write(m, p.id, null)) })
            is HubUi.MailPage.Write -> ComposeScreen(p.mode, p.id, p.acct, onBack = { ui.popMail(e.level) })
        }
    }, page = { n -> MailPageView(ui, n) })
}

@Composable
private fun HubHeader(ui: HubUi, version: Int) {
    val c = Bb10.colors
    val t = Bb10.typography
    val title = remember(version, ui.filter) { ui.title() }
    Column(Modifier.fillMaxWidth().background(HubLook.header())) {
        Row(
            Modifier.fillMaxWidth().height(bb(84f))
                .clickable(remember { MutableInteractionSource() }, null) { ui.foldersOpen = !ui.foldersOpen }
                .padding(start = bb(20f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Bb10Text(title, style = t.title.tight().copy(fontWeight = FontWeight.Medium, fontSize = bbSp(34.4f)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.paddingFromBaseline(top = bb(55.7f)))
            }
            HeaderButton(if (ui.foldersOpen) Bb10Icons.ChevronUp else Bb10Icons.ChevronDown, "Folders", bb(88f), bb(40f)) { ui.foldersOpen = !ui.foldersOpen }
            Box(Modifier.width(1.dp).height(bb(60f)).background(c.divider))
            Spacer(Modifier.width(bb(13f)))
            HeaderButton(SelectIcon, "Select", bb(88f), bb(40f), tint = if (ui.selecting) c.accent else null) { ui.selecting = !ui.selecting }
        }
        Box(Modifier.fillMaxWidth().height(bb(4f)).background(c.accent))
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, desc: String, width: androidx.compose.ui.unit.Dp = 52.dp,
                         size: androidx.compose.ui.unit.Dp = Bb10.dimens.iconSize, tint: Color? = null, onClick: () -> Unit) {
    val hl = rememberBb10Highlight()
    Box(Modifier.width(width).fillMaxHeight().bb10Clickable(highlight = hl, onClick = onClick), contentAlignment = Alignment.Center) {
        Bb10OnHighlight(hl) {
            if (tint != null && !hl.active) duc1607.bb10.designsystem.components.Bb10Icon(icon, contentDescription = desc, tint = tint, size = size)
            else duc1607.bb10.designsystem.components.Bb10Icon(icon, contentDescription = desc, size = size)
        }
    }
}

@Composable
private fun FolderDrop(ui: HubUi, version: Int) {
    val c = Bb10.colors
    val hub = LauncherApp.get().hub
    val pkgs = remember(version) { ui.accounts() }
    val q = ui.folderQuery.trim()
    data class F(val key: String?, val label: String, val icon: ImageBitmap?, val tint: Boolean)
    val all = remember(version, pkgs) {
        listOf(F(HubUi.PRIORITY, "Priority Hub", ui.glyph(R.drawable.ic_priority), true),
            F(HubUi.ATTACH, "Attachments", ui.glyph(R.drawable.ic_attach), true)) +
            pkgs.map { p -> ui.mono(p).let { (ic, tint) -> F(p, LauncherApp.get().apps.labelForPackage(p), ic, tint) } }
    }
    val shown = if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) }
    BackHandler { ui.foldersOpen = false }
    Column(Modifier.fillMaxWidth().background(if (c.isDark) c.surface else Color(0xFFF7F7F7))
        .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.62f).dp)) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Bb10TextField(ui.folderQuery, { ui.folderQuery = it }, placeholder = "Find a Folder", clearable = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HubLook.dayRule()))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
            if (q.isEmpty()) item { FolderRow(ui.glyph(R.drawable.ic_hub), true, "BlackBerry Hub", ui.filter == null, root = true) { ui.pick(null) } }
            items(shown, key = { it.key ?: "" }) { f -> FolderRow(f.icon, f.tint, f.label, ui.filter == f.key, root = false) { ui.pick(f.key) } }
        }
    }
}

@Composable
private fun FolderRow(icon: ImageBitmap?, tint: Boolean, label: String, selected: Boolean, root: Boolean, onClick: () -> Unit) {
    val c = Bb10.colors
    val hl = rememberBb10Highlight()
    Column {
        Row(Modifier.fillMaxWidth().height(bb(87f)).bb10Clickable(highlight = hl, flat = true, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically) {
            Bb10OnHighlight(hl) {
                Box(Modifier.width(bb(100f)), contentAlignment = Alignment.Center) {
                    if (root) Box(Modifier.size(bb(34f)).clip(RoundedCornerShape(bb(17f))).background(if (c.isDark) Color(0xFF444444) else Color(0xFFDDDDDD)),
                        contentAlignment = Alignment.Center) { Box(Modifier.width(11.dp).height(2.dp).background(c.textPrimary)) }
                }
                if (icon != null) Image(icon, null, Modifier.padding(start = if (root) 0.dp else 10.dp).size(if (root) bb(60f) else bb(48f)),
                    colorFilter = if (tint) ColorFilter.tint(c.accent) else null)
                Bb10Text(label, style = Bb10.typography.body.copy(fontSize = Bb10.typography.body.fontSize * 1.05f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (selected) Box(Modifier.width(bb(80f)), contentAlignment = Alignment.Center) {
                    duc1607.bb10.designsystem.components.Bb10Icon(Bb10Icons.Check, contentDescription = "Showing")
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HubLook.dayRule()))
    }
}

@Composable
private fun DayHeader(ui: HubUi, d: HubUi.Row.Header) {
    val c = Bb10.colors
    val hl = rememberBb10Highlight()
    Column(Modifier.fillMaxWidth().background(HubLook.row().copy(alpha = 0.9f))) {
        Box(Modifier.fillMaxWidth().height(bb(42f)).bb10Clickable(highlight = hl, flat = true) { ui.dayActions(d) }
            .padding(start = bb(18f), end = bb(18f))) {
            Bb10OnHighlight(hl) {
                Bb10Text(d.label, style = Bb10.typography.secondary.tight().copy(fontWeight = FontWeight.Medium, fontSize = bbSp(21.5f)),
                    color = c.textSecondary, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = bb(28f)))
            }
        }
        Box(Modifier.fillMaxWidth().height(bb(2f)).background(HubLook.dayRule()))
    }
}

private val GREY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun HubRow(ui: HubUi, it: HubItem, version: Int, focus: Boolean) {
    val c = Bb10.colors
    val t = Bb10.typography
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hl = rememberBb10Highlight()
    val requester = remember { FocusRequester() }
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    if (focus) LaunchedEffect(ui.focusFirst) { if (ui.focusFirst > 0) runCatching { requester.requestFocus() } }
    val read = it.read
    val icon = remember(it.pkg) { ui.icon(it.pkg) }
    val call = remember(it.id, it.text) { if (HubStore.isCall(it)) ui.callGlyph(it) else null }
    val bell = remember(it.pkg) { call == null && LauncherApp.get().isSystemNotifier(it.pkg) }
    val pic = remember(it.id, version) { if (call == null && !bell) ui.picture(it) else null }
    val photo = remember(it.id, version) { LauncherApp.get().hub.photo(it)?.asImageBitmap() }
    val prio = remember(version, it.id) { LauncherApp.get().hub.isPriority(it) }
    val h24 = DateFormat.is24HourFormat(ctx)
    val time = remember(it.time, h24) { DateFormat.format(if (h24) "HH:mm" else "h:mm a", it.time).toString() }
    val body = remember(it.text, it.sub) {
        if (it.sub.isNotEmpty() && !it.text.startsWith(it.sub)) it.sub + " • " + it.text else it.text
    }
    val titleText = remember(it.title) { smallEmoji(it.title) }
    val bodyText = remember(body) { smallEmoji(body) }
    Column(Modifier.background(HubLook.row())) {
        Row(
            Modifier.fillMaxWidth().height(bb(83f))
                .onPlaced { lc -> placed[0] = lc }
                .focusRequester(requester)
                .onFocusChanged { f -> if (f.isFocused) ui.focusedId = it.id }
                .bb10Clickable(highlight = hl, flat = true, onLongClick = { ui.showActions(it) }) {
                    val bounds = placed[0]?.takeIf { lc -> lc.isAttached }?.let { lc ->
                        val r = lc.boundsInWindow()
                        val loc = IntArray(2).also { a -> ui.listView.getLocationOnScreen(a) }
                        android.graphics.Rect(r.left.toInt() + loc[0], r.top.toInt() + loc[1], r.right.toInt() + loc[0], r.bottom.toInt() + loc[1])
                    }
                    ui.open(it, bounds)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Bb10OnHighlight(hl) {
                Box(Modifier.width(bb(96f)).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (call != null) Image(call, null, Modifier.size(bb(52f)))
                    else if (bell) ui.glyph(R.drawable.ic_bell)?.let { b ->
                        Image(b, null, Modifier.size(bb(50f)), colorFilter = ColorFilter.tint(Color(
                            if (read && !hl.active) LauncherApp.BELL_READ else LauncherApp.BELL_NEW)))
                    }
                    else if (pic != null) Box(Modifier.size(bb(56f))) {
                        Image(pic, null, Modifier.size(bb(52f)).clip(RoundedCornerShape(bb(6f))),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        if (icon != null) Image(icon, null, Modifier.size(bb(24f)).align(Alignment.BottomEnd))
                    }
                    else if (icon != null) Image(
                        icon, null, Modifier.size(bb(52f)),
                        colorFilter = if (read && !hl.active) GREY else null,
                        alpha = if (read && !hl.active) 0.8f else 1f,
                    )
                }
                val sel = androidx.compose.animation.core.animateFloatAsState(if (ui.selecting) 1f else 0f, tween(220), label = "select").value
                val dayRule = HubLook.dayRule()
                if (sel > 0f) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(dayRule.copy(alpha = dayRule.alpha * sel)))
                val two = body.isNotEmpty()
                val endPad = bb(23f) + (bb(10f) - bb(23f)) * sel
                Row(Modifier.weight(1f).fillMaxHeight().padding(start = bb(8f) * sel, end = endPad)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val titleBase = bb(if (two) 35f else 52f)
                        Row(Modifier.fillMaxWidth()) {
                            Bb10Text(titleText, style = t.body.tight().copy(fontWeight = if (read) FontWeight.Normal else FontWeight.Medium,
                                fontSize = bbSp(29f)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).paddingFromBaseline(top = titleBase))
                            if (prio) Bb10Text("!", style = t.title.tight().copy(fontWeight = FontWeight.Bold, fontSize = bbSp(29f)), color = Color(0xFFE53935),
                                modifier = Modifier.padding(start = bb(8f)).paddingFromBaseline(top = titleBase))
                        }
                        if (two) Bb10Text(bodyText, style = t.secondary.tight().copy(fontSize = bbSp(25.5f)), color = c.textSecondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().paddingFromBaseline(top = bb(68f)))
                    }
                    if (photo != null) Image(photo, null, Modifier.align(Alignment.CenterVertically).padding(start = bb(10f))
                        .size(bb(64f)).clip(RoundedCornerShape(bb(4f))), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Bb10Text(time, style = t.caption.tight().copy(fontSize = bbSp(21f)), color = c.textSecondary, maxLines = 1,
                        modifier = Modifier.padding(start = bb(12f)).paddingFromBaseline(top = bb(34f)))
                }
                if (sel > 0f) {
                    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(dayRule.copy(alpha = dayRule.alpha * sel)))
                    val dl = rememberBb10Highlight()
                    Box(Modifier.width(bb(90f) * sel).fillMaxHeight().clipToBounds()) {
                        Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(bb(90f)).fillMaxHeight()
                            .graphicsLayer { alpha = sel }
                            .then(if (ui.selecting) Modifier.bb10Clickable(highlight = dl) { LauncherApp.get().hub.delete(it) } else Modifier),
                            contentAlignment = Alignment.Center) {
                            Bb10OnHighlight(dl) { duc1607.bb10.designsystem.components.Bb10Icon(Bb10Icons.Delete, contentDescription = "Delete") }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HubLook.rule()))
    }
}

@Composable
private fun EmptyHub(ui: HubUi) {
    val c = Bb10.colors
    val granted = remember { ui.accessGranted() }
    Box(Modifier.fillMaxSize().padding(24.dp).let { m -> if (!granted) m.clickable(remember { MutableInteractionSource() }, null) { ui.requestAccess() } else m },
        contentAlignment = Alignment.Center) {
        Bb10Text(
            when {
                !granted -> "The Hub needs notification access.\n\nTap here to turn it on."
                ui.query.isNotEmpty() -> "No results"
                else -> "No messages"
            },
            style = Bb10.typography.body, color = c.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ReplyBar(ui: HubUi, target: HubItem) {
    val c = Bb10.colors
    Row(Modifier.fillMaxWidth().background(c.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Bb10TextField(ui.replyText, { ui.replyText = it }, placeholder = "Reply to " + target.title, singleLine = false,
                startEditing = true, onDone = { ui.sendReply() })
        }
        HeaderButton(duc1607.bb10.designsystem.icons.Bb10Icons.Check, "Send") { ui.sendReply() }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AccountsPanel(ui: HubUi, version: Int) {
    val c = Bb10.colors
    if (ui.accountsOpen) BackHandler { ui.accountsOpen = false }
    val open = ui.accountsOpen
    val slide = androidx.compose.animation.core.animateFloatAsState(if (open) 1f else 0f, tween(if (open) 180 else 150), label = "accounts")
    val fullW = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    Box(Modifier.fillMaxSize()
        .drawBehind { if (slide.value > 0f) drawRect(c.scrim.copy(alpha = c.scrim.alpha * slide.value)) }
        .then(if (open) Modifier.clickable(remember { MutableInteractionSource() }, null) { ui.accountsOpen = false } else Modifier))
    run {
        BoxWithConstraints(Modifier.fillMaxHeight().offset {
            androidx.compose.ui.unit.IntOffset(-((1f - slide.value) * (fullW * 0.78f + 4)).toInt(), 0)
        }.focusProperties { enter = { if (open) FocusRequester.Default else FocusRequester.Cancel } }.focusGroup()) {
            val hub = LauncherApp.get().hub
            val pkgs = remember(version) { ui.accounts() }
            Column(Modifier.width(maxWidth * 0.78f).fillMaxHeight().background(c.surface)) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                    item { AccountRow(ui, ui.glyph(R.drawable.ic_hub), true, "BlackBerry Hub", hub.unread(null), ui.filter == null) { ui.pick(null) } }
                    item { AccountRow(ui, ui.glyph(R.drawable.ic_priority), true, "Priority Hub", hub.unreadPriority(), ui.filter == HubUi.PRIORITY) { ui.pick(HubUi.PRIORITY) } }
                    item { Box(Modifier.fillMaxWidth().height(1.dp).background(c.accent)) }
                    items(pkgs, key = { it }) { p ->
                        AccountRow(ui, ui.icon(p), false, LauncherApp.get().apps.labelForPackage(p), hub.unread(p), ui.filter == p) { ui.pick(p) }
                    }
                }
                Bb10Divider()
                AccountRow(ui, ui.glyph(R.drawable.ic_mail), true, "Email accounts…", 0, false) {
                    ui.accountsOpen = false
                    val ctx = ui.listView.context
                    ctx.startActivity(MailActivity.accounts(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}

@Composable
private fun AccountRow(ui: HubUi, icon: ImageBitmap?, tint: Boolean, label: String, unread: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Bb10.colors
    val hl = rememberBb10Highlight()
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(if (selected) c.accent.copy(alpha = 0.15f) else Color.Transparent)
            .bb10Clickable(highlight = hl, flat = true, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Bb10OnHighlight(hl) {
            if (icon != null) Image(icon, null, Modifier.size(28.dp), colorFilter = if (tint) ColorFilter.tint(c.accent) else null)
            else Spacer(Modifier.size(28.dp))
            Bb10Text(label, style = Bb10.typography.body.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (unread > 0) Bb10Text(unread.toString(), style = Bb10.typography.secondary.copy(fontWeight = FontWeight.Bold), color = c.accent)
        }
    }
}

@Composable
private fun Agenda(ui: HubUi) {
    val c = Bb10.colors
    val t = Bb10.typography
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val live = LauncherApp.get().live
    val events = remember(ui.agendaOpen) { if (live.canReadCalendar()) live.upcoming() else emptyList() }
    Column(Modifier.fillMaxWidth().background(HubLook.row()).clickable(remember { MutableInteractionSource() }, null) { ui.agendaOpen = false }) {
        val note: String? = when {
            !live.canReadCalendar() -> "Calendar access is off. Settings › Set everything up with root turns it on."
            events.isEmpty() -> "No upcoming events"
            else -> null
        }
        if (note != null) Box(Modifier.fillMaxWidth().height(bb(92f))) {
            Bb10Text(note, style = t.body.tight().copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = bbSp(23f)),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = bb(20f)).paddingFromBaseline(top = bb(54f)))
        }
        val h24 = DateFormat.is24HourFormat(ctx)
        val fmt = if (h24) "HH:mm" else "h:mm a"
        val now = System.currentTimeMillis()
        events.take(6).forEach { e ->
            val today = DateUtils.isToday(e.begin)
            val whenTxt = when {
                e.allDay && today -> "Today"
                e.begin <= now && e.end > now -> "Now"
                today -> {
                    val mins = ((e.begin - now) / 60000L).toInt()
                    if (mins >= 60) "In ${mins / 60} hr ${mins % 60} mins" else "In $mins mins"
                }
                DateUtils.isToday(e.begin - DateUtils.DAY_IN_MILLIS) -> "Tomorrow"
                else -> DateFormat.format("EEE, MMM d", e.begin).toString()
            }
            val times = if (e.allDay) "All day" else DateFormat.format(fmt, e.begin).toString() + " - " + DateFormat.format(fmt, e.end)
            val place = e.location.isNotEmpty()
            Row(Modifier.fillMaxWidth().height(bb(if (place) 106f else 85f))) {
                Box(Modifier.width(bb(6f)).fillMaxHeight().background(Color(0xFFFC9024)))
                Box(Modifier.weight(1f).fillMaxHeight().padding(start = bb(13f), end = bb(23f))) {
                    Row(Modifier.fillMaxWidth()) {
                        Bb10Text(times, style = t.body.tight().copy(fontSize = bbSp(23f)), color = c.textSecondary, maxLines = 1,
                            modifier = Modifier.weight(1f).paddingFromBaseline(top = bb(33f)))
                        Bb10Text(whenTxt, style = t.body.tight().copy(fontSize = bbSp(23f), fontWeight = if (today) FontWeight.Medium else FontWeight.Normal),
                            color = if (today) c.textPrimary else c.textSecondary, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = bb(33f)))
                    }
                    Bb10Text(e.title, style = t.body.tight().copy(fontSize = bbSp(29f), fontWeight = if (today) FontWeight.Medium else FontWeight.Normal),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.paddingFromBaseline(top = bb(68f)))
                    if (place) Bb10Text(e.location, style = t.secondary.tight().copy(fontSize = bbSp(21f)), color = c.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.paddingFromBaseline(top = bb(94f)))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HubLook.rule()))
        }
        Box(Modifier.fillMaxWidth().height(bb(20f)).background(if (c.isDark) Color(0xFF2A2A2A) else Color(0xFFE5E5E5)))
    }
}
