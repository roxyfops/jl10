package com.bb10.launcher.mail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.bb10.launcher.LauncherApp
import com.bb10.launcher.R
import com.bb10.launcher.hub.HtmlText
import com.bb10.launcher.hub.HubItem
import com.bb10.launcher.hub.HubStore
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.Properties
import java.util.concurrent.Executors
import javax.mail.Address
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

data class MailBody(val html: String?, val text: String?, val attachments: List<Attachment>)

data class Attachment(val path: String, val name: String, val size: Int, val type: String)

data class Draft(
    val to: String, val cc: String, val bcc: String, val subject: String, val body: String,
    val inReplyTo: String = "", val refs: String = "",
)

object Mail {
    private const val TAG = "BB10Mail"
    private const val KEEP = 300
    private const val FIRST = 60
    private const val CHANNEL = "mail"

    private val net = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val db by lazy { MailDb(app) }

    private val _syncing = MutableStateFlow<Set<String>>(emptySet())
    val syncing: StateFlow<Set<String>> = _syncing
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors

    fun hasAttachment(hubId: String): Boolean = runCatching {
        if (!::app.isInitialized) return false
        val (acct, uid) = MailMsg.parse(hubId) ?: return false
        db.message(acct, uid)?.attach == true
    }.getOrDefault(false)

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        HubStore.mailHook = object : HubStore.MailHook {
            override fun read(items: List<HubItem>, read: Boolean) = queue(items, if (read) "seen" else "unseen")
            override fun deleted(items: List<HubItem>) = queue(items, "delete")
        }
        if (MailAccounts.list(app).isNotEmpty()) {
            schedule()
            MailPush.start(app)
        }
    }

    fun accounts(): List<MailAccount> = MailAccounts.list(app)

    private fun proto(sec: Security, base: String) = if (sec == Security.SSL) base + "s" else base

    private fun session(a: MailAccount, readTimeout: Int = 30_000): Session {
        val p = Properties()
        val ip = proto(a.imapSec, "imap")
        val sp = proto(a.smtpSec, "smtp")
        p["mail.store.protocol"] = ip
        for ((k, v) in listOf("connectiontimeout" to "15000", "timeout" to readTimeout.toString(), "writetimeout" to "30000")) {
            p["mail.$ip.$k"] = v
            p["mail.$sp.$k"] = v
        }
        p["mail.$ip.peek"] = "true"
        p["mail.$ip.ssl.checkserveridentity"] = "true"
        p["mail.$sp.ssl.checkserveridentity"] = "true"
        if (a.imapSec == Security.STARTTLS) {
            p["mail.imap.starttls.enable"] = "true"
            p["mail.imap.starttls.required"] = "true"
        }
        p["mail.$sp.auth"] = "true"
        if (a.smtpSec == Security.STARTTLS) {
            p["mail.smtp.starttls.enable"] = "true"
            p["mail.smtp.starttls.required"] = "true"
        }
        p["mail.$sp.localhost"] = "localhost"
        return Session.getInstance(p, null)
    }

    private fun store(a: MailAccount, pw: String): Store {
        val s = session(a).getStore(proto(a.imapSec, "imap"))
        s.connect(a.imapHost, a.imapPort, a.user, pw)
        return s
    }

    private fun pw(a: MailAccount) = MailAccounts.password(app, a) ?: throw IllegalStateException("No password saved")

    internal fun idleStore(a: MailAccount): Store {
        val s = session(a, MailPush.READ_TIMEOUT_MS).getStore(proto(a.imapSec, "imap"))
        s.connect(a.imapHost, a.imapPort, a.user, pw(a))
        return s
    }

    private val again = java.util.Collections.synchronizedSet(HashSet<String>())

    internal fun pushed(a: MailAccount) {
        if (a.id in _syncing.value) again += a.id else sync(a)
    }

    fun test(a: MailAccount, pw: String): String? {
        try {
            store(a, pw).use { s -> s.getFolder("INBOX").exists() }
        } catch (e: Exception) {
            return "Incoming (IMAP): " + explain(e)
        }
        try {
            val t = session(a).getTransport(proto(a.smtpSec, "smtp"))
            t.connect(a.smtpHost, a.smtpPort, a.user, pw)
            t.close()
        } catch (e: Exception) {
            return "Outgoing (SMTP): " + explain(e)
        }
        return null
    }

    private fun networkBlocked(): Boolean = runCatching {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.activeNetworkInfo?.detailedState == android.net.NetworkInfo.DetailedState.BLOCKED
    }.getOrDefault(false)

    private fun explain(e: Throwable): String {
        if (networkBlocked()) return "JL10 isn't allowed to use the network. Turn on Settings › Apps › JL10 › " +
            "Mobile data & Wi-Fi › Network access, then try again."
        val m =generateSequence(e) { it.cause }.mapNotNull { it.message }.firstOrNull() ?: e.javaClass.simpleName
        return when {
            m.contains("AUTHENTICATE", true) || m.contains("authentication", true) || m.contains("Invalid credentials", true) ||
                m.contains("LOGIN failed", true) || m.contains("535") -> "The password wasn't accepted. Many providers need an app password: see above."
            m.contains("UnknownHost", true) || e is java.net.UnknownHostException -> "Server not found (check the address, or the connection)"
            m.contains("timed out", true) -> "The server didn't answer in time"
            m.contains("Couldn't connect", true) -> "Couldn't reach the server (check the address and port, and the connection)"
            else -> m
        }
    }

    fun syncAll(done: (() -> Unit)? = null) {
        val list = accounts()
        if (list.isEmpty()) {
            done?.invoke()
            return
        }
        list.forEach { sync(it) }
        if (done != null) net.execute { main.post(done) }
    }

    fun sync(a: MailAccount) {
        if (a.id in _syncing.value) return
        _syncing.value = _syncing.value + a.id
        net.execute {
            try {
                flush(a)
                syncInbox(a)
                _errors.value = _errors.value - a.id
            } catch (e: Throwable) {
                Log.w(TAG, "sync ${a.id}", e)
                _errors.value = _errors.value + (a.id to explain(e))
            } finally {
                _syncing.value = _syncing.value - a.id
                if (again.remove(a.id)) main.post { sync(a) }
            }
        }
    }

    private fun syncInbox(a: MailAccount) {
        val first = db.box(a.id) == null
        val announce = ArrayList<MailMsg>()
        store(a, pw(a)).use { s ->
            val f = s.getFolder("INBOX") as IMAPFolder
            f.open(Folder.READ_ONLY)
            try {
                val validity = f.uidValidity
                var box = db.box(a.id)
                if (box != null && box.first != validity) {
                    db.forget(a.id)
                    box = null
                }
                val fresh: Array<Message> = if (box == null) {
                    val n = f.messageCount
                    if (n == 0) emptyArray() else f.getMessages(maxOf(1, n - FIRST + 1), n)
                } else {
                    f.getMessagesByUID(box.second + 1, UIDFolder.LASTUID).filter { f.getUID(it) > box.second }.toTypedArray()
                }
                val fp = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID)
                    add(FetchProfile.Item.CONTENT_INFO); add("References"); add("Message-ID")
                }
                f.fetch(fresh, fp)
                var last = box?.second ?: 0L
                for (m in fresh.sortedByDescending { f.getUID(it) }.take(FIRST)) {
                    val uid = f.getUID(m)
                    last = maxOf(last, uid)
                    val wasSeen = m.isSet(Flags.Flag.SEEN)
                    val body = runCatching { extract(m) }.getOrNull()
                    if (body != null) saveBody(a.id, uid, body)
                    val msg = headers(a.id, uid, m, body).copy(seen = wasSeen)
                    db.put(msg)
                    if (!first && !msg.seen) announce += msg
                }
                val kept = db.messages(a.id, KEEP)
                if (kept.isNotEmpty()) {
                    val lo = kept.minOf { it.uid }
                    val now = f.getMessagesByUID(lo, maxOf(last, kept.maxOf { it.uid }))
                    f.fetch(now, FetchProfile().apply { add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) })
                    val seen = HashMap<Long, Boolean>()
                    for (m in now) if (m != null) seen[f.getUID(m)] = m.isSet(Flags.Flag.SEEN)
                    for (k in kept) {
                        val s2 = seen[k.uid]
                        if (s2 == null) {
                            db.delete(a.id, k.uid)
                            MailFiles.forget(app, a.id, k.uid)
                        } else if (s2 != k.seen) db.setSeen(a.id, k.uid, s2)
                    }
                }
                db.trim(a.id, KEEP)
                db.setBox(a.id, validity, last)
            } finally {
                runCatching { f.close(false) }
            }
        }
        publish(a, announce = !first)
        if (announce.isNotEmpty()) notifyNew(a, announce)
    }

    fun publish(a: MailAccount, announce: Boolean = false) {
        val items = db.messages(a.id, KEEP).map { hubItem(a, it) }
        main.post {
            LauncherApp.get().hub.putMail(a.pkg, items, announce)
            if (items.none { !it.read }) cancelNotification(a)
        }
    }

    private fun hubItem(a: MailAccount, m: MailMsg) = HubItem().apply {
        id = m.hubId
        key = m.hubId
        pkg = a.pkg
        title = m.sender
        sub = HtmlText.line(m.subject).ifBlank { "(no subject)" }
        text = HtmlText.line(m.snippet)
        time = m.date
        read = m.seen
        active = false
        persistent = false
    }

    private fun addrs(a: Array<Address>?): String =
        a?.joinToString(", ") { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() } ?: ""

    private fun headers(acct: String, uid: Long, m: Message, body: MailBody?): MailMsg {
        val from = m.from?.firstOrNull() as? InternetAddress
        val text = body?.text ?: body?.html?.let { stripHtml(it) } ?: ""
        return MailMsg(
            acct, uid,
            m.getHeader("Message-ID")?.firstOrNull() ?: "",
            m.getHeader("References")?.firstOrNull() ?: "",
            from?.personal ?: "", from?.address ?: "",
            addrs(m.getRecipients(Message.RecipientType.TO)), addrs(m.getRecipients(Message.RecipientType.CC)),
            addrs(m.replyTo),
            HtmlText.line(m.subject ?: ""),
            HtmlText.line(text).take(180),
            (m.receivedDate ?: m.sentDate ?: Date()).time,
            m.isSet(Flags.Flag.SEEN), m.isSet(Flags.Flag.FLAGGED),
            body?.attachments?.isNotEmpty() == true,
        )
    }

    private fun isAttachment(p: Part): Boolean =
        Part.ATTACHMENT.equals(p.disposition, true) || (p.fileName != null && !p.isMimeType("text/*"))

    private fun extract(m: Part): MailBody {
        var html: String? = null
        var text: String? = null
        val atts = ArrayList<Attachment>()
        val inline = HashMap<String, Part>()
        fun walk(p: Part, path: String) {
            when {
                p.isMimeType("multipart/*") -> {
                    val mp = p.content as Multipart
                    for (i in 0 until mp.count) walk(mp.getBodyPart(i), if (path.isEmpty()) "${i + 1}" else "$path.${i + 1}")
                }
                p.isMimeType("text/html") && !isAttachment(p) -> if (html == null) html = p.content as? String
                p.isMimeType("text/plain") && !isAttachment(p) -> if (text == null) text = p.content as? String
                else -> {
                    val cid = (p as? MimeBodyPart)?.contentID?.trim('<', '>')
                    if (cid != null && p.isMimeType("image/*") && !Part.ATTACHMENT.equals(p.disposition, true)) inline[cid] = p
                    else atts += Attachment(path.ifEmpty { "1" }, p.fileName ?: "attachment", p.size, p.contentType.substringBefore(';'))
                }
            }
        }
        walk(m, "")
        val plain = text
        if (plain != null && HtmlText.hasHtml(plain)) {
            if (html == null) html = plain
            text = null
        }
        var h = html
        if (h != null) for ((cid, p) in inline) {
            if (!h!!.contains("cid:$cid")) continue
            runCatching {
                val bytes = p.inputStream.use { it.readBytes() }
                if (bytes.size < 1_500_000) h = h!!.replace("cid:$cid",
                    "data:${p.contentType.substringBefore(';')};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
        return MailBody(h, text, atts)
    }

    private fun saveBody(acct: String, uid: Long, b: MailBody) = MailFiles.save(app, acct, uid, b)

    fun body(a: MailAccount, uid: Long): MailBody? {
        MailFiles.load(app, a.id, uid)?.let { return it }
        return runCatching {
            store(a, pw(a)).use { s ->
                val f = s.getFolder("INBOX") as IMAPFolder
                f.open(Folder.READ_ONLY)
                try {
                    val m = f.getMessageByUID(uid) ?: return null
                    extract(m).also { saveBody(a.id, uid, it) }
                } finally {
                    runCatching { f.close(false) }
                }
            }
        }.getOrNull()
    }

    fun attachment(a: MailAccount, uid: Long, att: Attachment): File? = runCatching {
        val dir = File(app.cacheDir, "mail-att/${a.id.hashCode()}-$uid").apply { mkdirs() }
        val out = File(dir, att.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        if (out.exists() && out.length() > 0) return out
        store(a, pw(a)).use { s ->
            val f = s.getFolder("INBOX") as IMAPFolder
            f.open(Folder.READ_ONLY)
            try {
                var p: Part = f.getMessageByUID(uid) ?: return null
                for (i in att.path.split('.')) {
                    val c = p.content
                    if (c is Multipart) p = c.getBodyPart(i.toInt() - 1)
                }
                p.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it) } }
            } finally {
                runCatching { f.close(false) }
            }
        }
        out
    }.getOrNull()

    fun message(acct: String, uid: Long): MailMsg? = db.message(acct, uid)

    private val pendingLock = Any()

    private fun queue(items: List<HubItem>, op: String) {
        val byAcct = items.mapNotNull { MailMsg.parse(it.id) }.groupBy({ it.first }, { it.second })
        synchronized(pendingLock) {
            val p = pending()
            for ((acct, uids) in byAcct) {
                p.put(JSONObject().put("acct", acct).put("op", op).put("uids", JSONArray(uids)))
                for (u in uids) when (op) {
                    "seen" -> db.setSeen(acct, u, true)
                    "unseen" -> db.setSeen(acct, u, false)
                    "delete" -> {
                        db.delete(acct, u)
                        MailFiles.forget(app, acct, u)
                    }
                }
            }
            savePending(p)
        }
        for (acct in byAcct.keys) {
            val a = MailAccounts.byId(app, acct) ?: continue
            net.execute { runCatching { flush(a) }.onFailure { Log.w(TAG, "later: $op", it) } }
            if (op != "delete") main.post { if (items.all { it.read }) maybeCancel(a) }
        }
    }

    private fun pending(): JSONArray =
        runCatching { JSONArray(app.getSharedPreferences("mail_pending", 0).getString("ops", "[]")) }.getOrDefault(JSONArray())

    private fun savePending(a: JSONArray) {
        app.getSharedPreferences("mail_pending", 0).edit().putString("ops", a.toString()).apply()
    }

    private fun flush(a: MailAccount) {
        val mine = synchronized(pendingLock) {
            val p = pending()
            (0 until p.length()).map { p.getJSONObject(it) }.filter { it.getString("acct") == a.id }
        }
        if (mine.isEmpty()) return
        store(a, pw(a)).use { s ->
            val f = s.getFolder("INBOX") as IMAPFolder
            f.open(Folder.READ_WRITE)
            try {
                for (o in mine) {
                    val ua = o.getJSONArray("uids")
                    val uids = LongArray(ua.length()) { ua.getLong(it) }
                    val msgs = f.getMessagesByUID(uids).filterNotNull().toTypedArray()
                    if (msgs.isEmpty()) continue
                    when (o.getString("op")) {
                        "seen" -> f.setFlags(msgs, Flags(Flags.Flag.SEEN), true)
                        "unseen" -> f.setFlags(msgs, Flags(Flags.Flag.SEEN), false)
                        "delete" -> {
                            val trash = special(s, "\\Trash", listOf("Trash", "[Gmail]/Trash", "[Google Mail]/Trash", "Deleted Items", "Deleted Messages", "INBOX.Trash"))
                            if (trash != null && trash.fullName != f.fullName) f.moveMessages(msgs, trash)
                            else {
                                f.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
                                f.expunge()
                            }
                        }
                    }
                }
            } finally {
                runCatching { f.close(false) }
            }
        }
        synchronized(pendingLock) {
            val p = pending()
            val left = JSONArray()
            for (i in 0 until p.length()) {
                val o = p.getJSONObject(i)
                if (mine.none { it.toString() == o.toString() }) left.put(o)
            }
            savePending(left)
        }
    }

    private fun special(s: Store, attr: String, names: List<String>): Folder? {
        val all = runCatching { s.defaultFolder.list("*") }.getOrNull() ?: return null
        all.firstOrNull { f -> (f as? IMAPFolder)?.attributes?.any { it.equals(attr, true) } == true }?.let { return it }
        for (n in names) all.firstOrNull { it.fullName.equals(n, true) }?.let { return it }
        return null
    }

    fun send(a: MailAccount, d: Draft, done: (String?) -> Unit) {
        net.execute {
            val err = try {
                val sess = session(a)
                val m = MimeMessage(sess)
                m.setFrom(InternetAddress(a.email, a.name.ifBlank { null }, "UTF-8"))
                m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(d.to, false))
                if (d.cc.isNotBlank()) m.setRecipients(Message.RecipientType.CC, InternetAddress.parse(d.cc, false))
                if (d.bcc.isNotBlank()) m.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(d.bcc, false))
                m.setSubject(d.subject, "UTF-8")
                m.sentDate = Date()
                if (d.inReplyTo.isNotBlank()) {
                    m.setHeader("In-Reply-To", d.inReplyTo)
                    m.setHeader("References", (d.refs + " " + d.inReplyTo).trim())
                }
                m.setText(d.body, "UTF-8")
                m.saveChanges()
                val t = sess.getTransport(proto(a.smtpSec, "smtp"))
                t.connect(a.smtpHost, a.smtpPort, a.user, pw(a))
                try {
                    t.sendMessage(m, m.allRecipients)
                } finally {
                    t.close()
                }
                if (!a.smtpHost.contains("gmail") && !a.smtpHost.contains("office365") && !a.smtpHost.contains("outlook")) runCatching {
                    store(a, pw(a)).use { s ->
                        special(s, "\\Sent", listOf("Sent", "Sent Items", "Sent Messages", "INBOX.Sent", "Sent Mail"))?.let { sent ->
                            m.setFlag(Flags.Flag.SEEN, true)
                            sent.appendMessages(arrayOf(m))
                        }
                    }
                }
                null
            } catch (e: Throwable) {
                Log.w(TAG, "send", e)
                explain(e)
            }
            main.post { done(err) }
        }
    }

    fun added(a: MailAccount) {
        schedule()
        sync(a)
        MailPush.start(app)
        MailPush.refresh()
    }

    fun removed(a: MailAccount) {
        MailPush.refresh()
        net.execute {
            db.forget(a.id)
            MailFiles.forgetAccount(app, a.id)
        }
        LauncherApp.get().hub.removeMail(a.pkg)
        cancelNotification(a)
    }

    private fun schedule() {
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB) != null) return
        js.schedule(JobInfo.Builder(JOB, ComponentName(app, MailSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60_000L)
            .setPersisted(true)
            .build())
    }

    private const val JOB = 4410

    private fun notifyNew(a: MailAccount, msgs: List<MailMsg>) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Email", NotificationManager.IMPORTANCE_DEFAULT))
        val unread = db.messages(a.id, KEEP).count { !it.seen }
        val newest = msgs.maxByOrNull { it.date } ?: return
        val open = PendingIntent.getActivity(app, a.id.hashCode(),
            Intent(app, com.bb10.launcher.HubShortcutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_mail)
            .setColor(a.color)
            .setContentTitle(if (msgs.size == 1) newest.sender else "${msgs.size} new emails")
            .setContentText(if (msgs.size == 1) newest.subject else msgs.joinToString(", ") { it.sender })
            .setSubText(a.email)
            .setNumber(unread)
            .setWhen(newest.date)
            .setCategory(Notification.CATEGORY_EMAIL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(a.id.hashCode(), n) }
    }

    private fun maybeCancel(a: MailAccount) {
        val hub = LauncherApp.get().hub
        if (hub.unread(a.pkg) == 0) cancelNotification(a)
    }

    private fun cancelNotification(a: MailAccount) {
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(a.id.hashCode()) }
    }

    private val icons = HashMap<String, android.graphics.drawable.Drawable>()

    @JvmStatic
    fun icon(c: Context, pkg: String): android.graphics.drawable.Drawable? {
        if (!HubStore.isMailPkg(pkg)) return null
        icons[pkg]?.let { return it }
        val a = MailAccounts.byPkg(c, pkg)
        val d = c.getDrawable(R.drawable.ic_mail)?.mutate() ?: return null
        d.setTint(a?.color ?: 0xFF0092CC.toInt())
        icons[pkg] = d
        return d
    }

    @JvmStatic
    fun label(c: Context, pkg: String): String? =
        if (!HubStore.isMailPkg(pkg)) null else MailAccounts.byPkg(c, pkg)?.email ?: pkg.removePrefix(HubStore.MAIL)

    fun stripHtml(h: String): String = HtmlText.strip(h)

    const val SIGNATURE_KEY = "mail_signature"
    const val DEFAULT_SIGNATURE = "Sent from my BlackBerry 10 smartphone."

    fun signature(): String =
        com.bb10.launcher.LauncherApp.get().prefs.sp.getString(SIGNATURE_KEY, DEFAULT_SIGNATURE)?.trim() ?: ""

    fun setSignature(s: String) {
        com.bb10.launcher.LauncherApp.get().prefs.sp.edit().putString(SIGNATURE_KEY, s.trim()).apply()
    }

    fun prefixed(prefix: String, subject: String): String =
        if (subject.trim().startsWith(prefix, true)) subject.trim() else "$prefix ${subject.trim()}"
}

object MailFiles {
    private fun dir(c: Context, acct: String) = File(c.filesDir, "mail/${acct.hashCode()}").apply { mkdirs() }

    fun save(c: Context, acct: String, uid: Long, b: MailBody) {
        val o = JSONObject().put("html", b.html ?: JSONObject.NULL).put("text", b.text ?: JSONObject.NULL)
            .put("att", JSONArray().apply {
                b.attachments.forEach { put(JSONObject().put("path", it.path).put("name", it.name).put("size", it.size).put("type", it.type)) }
            })
        runCatching { File(dir(c, acct), "$uid.json").writeText(o.toString()) }
    }

    fun load(c: Context, acct: String, uid: Long): MailBody? = runCatching {
        val f = File(dir(c, acct), "$uid.json")
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val a = o.getJSONArray("att")
        var html = o.optString("html").takeIf { !o.isNull("html") && it.isNotEmpty() }
        var text = o.optString("text").takeIf { !o.isNull("text") && it.isNotEmpty() }
        if (text != null && HtmlText.hasHtml(text)) {
            if (html == null) html = text
            text = null
        }
        MailBody(
            html,
            text,
            (0 until a.length()).map { a.getJSONObject(it) }.map { Attachment(it.getString("path"), it.getString("name"), it.optInt("size"), it.optString("type")) },
        )
    }.getOrNull()

    fun forget(c: Context, acct: String, uid: Long) {
        File(dir(c, acct), "$uid.json").delete()
    }

    fun forgetAccount(c: Context, acct: String) {
        dir(c, acct).deleteRecursively()
    }
}
