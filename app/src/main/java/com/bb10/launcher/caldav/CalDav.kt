package com.bb10.launcher.caldav

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import android.util.Xml
import com.bb10.launcher.mail.MailAccounts
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CalDavAccount(val id: String, val url: String, val user: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("user", user).put("name", name)

    companion object {
        fun from(o: JSONObject) = CalDavAccount(o.getString("id"), o.getString("url"), o.getString("user"), o.optString("name"))
    }
}

object CalDavAccounts {
    private fun prefs(c: Context) = c.getSharedPreferences("caldav", Context.MODE_PRIVATE)

    fun list(c: Context): List<CalDavAccount> = runCatching {
        val a = JSONArray(prefs(c).getString("accounts", "[]"))
        (0 until a.length()).map { CalDavAccount.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(c: Context, acct: CalDavAccount, password: String?) {
        val all = list(c).filter { it.id != acct.id } + acct
        val a = JSONArray().also { j -> all.forEach { j.put(it.toJson()) } }
        val e = prefs(c).edit().putString("accounts", a.toString())
        if (password != null) e.putString("pw_" + acct.id, MailAccounts.encrypt(password))
        e.apply()
    }

    fun remove(c: Context, id: String) {
        val a = JSONArray().also { j -> list(c).filter { it.id != id }.forEach { j.put(it.toJson()) } }
        prefs(c).edit().putString("accounts", a.toString()).remove("pw_$id").remove("status_$id").apply()
    }

    fun password(c: Context, acct: CalDavAccount): String? =
        prefs(c).getString("pw_" + acct.id, null)?.let { runCatching { MailAccounts.decrypt(it) }.getOrNull() }

    fun setStatus(c: Context, id: String, s: String) = prefs(c).edit().putString("status_$id", s).apply()

    fun status(c: Context, id: String): String = prefs(c).getString("status_$id", "") ?: ""
}

object CalDav {
    private const val TAG = "BB10CalDav"
    private const val JOB = 0xCA1D
    private lateinit var app: Context
    private val bg = Executors.newSingleThreadExecutor()
    val syncing = MutableStateFlow<Set<String>>(emptySet())

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true).build()
    }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        schedule()
    }

    fun schedule() {
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        if (CalDavAccounts.list(app).isEmpty()) { js.cancel(JOB); return }
        if (js.getPendingJob(JOB) != null) return
        js.schedule(JobInfo.Builder(JOB, ComponentName(app, CalDavSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(30 * 60_000L).setPersisted(true).build())
    }

    fun syncAll(done: (() -> Unit)? = null) {
        val list = CalDavAccounts.list(app)
        bg.execute {
            list.forEach { syncNow(it) }
            done?.invoke()
        }
    }

    fun sync(a: CalDavAccount) = bg.execute { syncNow(a) }

    class Resp(val code: Int, val body: String, val etag: String?, val location: String?)

    private fun dav(method: String, url: String, user: String, pw: String, body: String? = null, depth: String? = null,
                    type: String = "application/xml; charset=utf-8", extra: Map<String, String> = emptyMap()): Resp {
        val rb = body?.toRequestBody(type.toMediaType())
        val b = Request.Builder().url(url).method(method, rb ?: if (method == "PROPFIND" || method == "REPORT") "".toRequestBody(type.toMediaType()) else null)
            .header("Authorization", Credentials.basic(user, pw, Charsets.UTF_8))
            .header("User-Agent", "JL10 BB10 Calendar")
        if (depth != null) b.header("Depth", depth)
        extra.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r ->
            return Resp(r.code, r.body?.string() ?: "", r.header("ETag"), r.request.url.toString())
        }
    }

    private fun check(r: Resp, what: String) {
        if (r.code == 401 || r.code == 403) throw Exception("The username or password wasn't accepted")
        if (r.code !in 200..299) throw Exception("$what: the server answered ${r.code}")
    }

    class DavItem(val href: String) {
        val props = HashMap<String, String>()
        val hrefs = HashMap<String, String>()
        var isCalendar = false
        val comps = HashSet<String>()
        var ok = true
    }

    private fun parse(xml: String): List<DavItem> {
        val out = ArrayList<DavItem>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(xml))
        var cur: DavItem? = null
        val path = ArrayList<String>()
        var text = StringBuilder()
        var propOk = true
        val pending = HashMap<String, String>()
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    path += n
                    text = StringBuilder()
                    when (n) {
                        "response" -> cur = null
                        "propstat" -> { propOk = true; pending.clear() }
                        "calendar" -> if (path.contains("resourcetype")) pending["__cal"] = "1"
                        "comp" -> if (path.contains("supported-calendar-component-set")) p.getAttributeValue(null, "name")?.let { pending["__comp_$it"] = "1" }
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val n = p.name
                    val t = text.toString().trim()
                    val parent = path.getOrNull(path.size - 2)
                    when {
                        n == "href" && parent == "response" -> cur = DavItem(Uri.decode(t)).also { out += it }
                        n == "href" && parent != null && parent != "response" -> pending["__href_$parent"] = t
                        n == "status" && parent == "propstat" -> propOk = t.contains(" 200")
                        n == "propstat" -> {
                            val c = cur
                            if (c != null && propOk) for ((k, v) in pending) when {
                                k == "__cal" -> c.isCalendar = true
                                k.startsWith("__comp_") -> c.comps += k.removePrefix("__comp_")
                                k.startsWith("__href_") -> c.hrefs[k.removePrefix("__href_")] = v
                                else -> c.props[k] = v
                            }
                            pending.clear()
                        }
                        path.contains("prop") && t.isNotEmpty() && n != "href" -> pending[n] = t
                    }
                    path.removeAt(path.size - 1)
                    text = StringBuilder()
                }
                XmlPullParser.END_DOCUMENT -> return out
            }
        }
    }

    private fun abs(base: String, href: String): String = runCatching { java.net.URI(base).resolve(href).toString() }.getOrDefault(href)

    class RemoteCal(val url: String, val name: String, val color: Int?, val ctag: String?)

    fun discover(input: String, user: String, pw: String): List<RemoteCal> {
        var url = input.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        if (url.contains("google.com")) throw Exception("Google Calendar needs Google sign-in (OAuth), which isn't supported: use another CalDAV server")
        val tryUrls = listOf(url, abs(url, "/.well-known/caldav"))
        var principal: String? = null
        var lastErr: Exception? = null
        for (u in tryUrls) {
            try {
                val r = dav("PROPFIND", u, user, pw, PROP_PRINCIPAL, "0")
                if (r.code == 401 || r.code == 403) check(r, "Sign in")
                if (r.code != 207) continue
                val items = parse(r.body)
                val base = r.location ?: u
                items.firstNotNullOfOrNull { it.hrefs["current-user-principal"] }?.let { principal = abs(base, it) }
                if (principal == null) principal = base
                break
            } catch (e: Exception) {
                lastErr = e
                if (e.message?.contains("password") == true) throw e
            }
        }
        val pr = principal ?: throw (lastErr ?: Exception("No CalDAV server found at that address"))
        val r2 = dav("PROPFIND", pr, user, pw, PROP_HOME, "0")
        check(r2, "Finding your calendars")
        val home = parse(r2.body).firstNotNullOfOrNull { it.hrefs["calendar-home-set"] }?.let { abs(r2.location ?: pr, it) } ?: pr
        val r3 = dav("PROPFIND", home, user, pw, PROP_CALS, "1")
        check(r3, "Listing your calendars")
        val cals = parse(r3.body).filter { it.isCalendar && (it.comps.isEmpty() || "VEVENT" in it.comps) }.map {
            RemoteCal(abs(r3.location ?: home, it.href), it.props["displayname"] ?: it.href.trimEnd('/').substringAfterLast('/'),
                it.props["calendar-color"]?.let { col -> parseColor(col) }, it.props["getctag"])
        }
        if (cals.isEmpty()) throw Exception("Signed in, but there are no calendars on this account")
        return cals
    }

    private fun parseColor(s: String): Int? = runCatching {
        val h = s.trim().removePrefix("#")
        val rgb = h.substring(0, 6).toLong(16).toInt()
        (0xFF shl 24) or rgb
    }.getOrNull()

    private const val NS = "xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:i=\"http://apple.com/ns/ical/\""
    private const val PROP_PRINCIPAL = "<?xml version=\"1.0\"?><d:propfind $NS><d:prop><d:current-user-principal/></d:prop></d:propfind>"
    private const val PROP_HOME = "<?xml version=\"1.0\"?><d:propfind $NS><d:prop><c:calendar-home-set/></d:prop></d:propfind>"
    private const val PROP_CALS = "<?xml version=\"1.0\"?><d:propfind $NS><d:prop><d:resourcetype/><d:displayname/><i:calendar-color/><cs:getctag/><c:supported-calendar-component-set/></d:prop></d:propfind>"

    private fun syncUri(u: Uri, a: CalDavAccount): Uri = u.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName(a))
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private fun accountName(a: CalDavAccount) = a.name

    private fun localCalendars(a: CalDavAccount): Map<String, Long> {
        val out = HashMap<String, Long>()
        app.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CAL_SYNC1),
            "${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC3}=?", arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, a.id), null)?.use { c ->
            while (c.moveToNext()) out[c.getString(1) ?: ""] = c.getLong(0)
        }
        return out
    }

    fun removeLocal(a: CalDavAccount) {
        localCalendars(a).values.forEach { id ->
            app.contentResolver.delete(ContentUris.withAppendedId(syncUri(Calendars.CONTENT_URI, a), id), null, null)
        }
    }

    private fun syncNow(a: CalDavAccount) {
        syncing.value = syncing.value + a.id
        try {
            val pw = CalDavAccounts.password(app, a) ?: throw Exception("No password saved")
            val remote = discover(a.url, a.user, pw)
            val local = localCalendars(a).toMutableMap()
            for (rc in remote) {
                val v = ContentValues().apply {
                    put(Calendars.CALENDAR_DISPLAY_NAME, rc.name)
                    put(Calendars.CALENDAR_COLOR, rc.color ?: 0xFF00A8DF.toInt())
                    put(Calendars.CAL_SYNC1, rc.url)
                    put(Calendars.CAL_SYNC3, a.id)
                }
                val id = local[rc.url]
                if (id == null) {
                    v.put(Calendars.ACCOUNT_NAME, accountName(a))
                    v.put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    v.put(Calendars.NAME, rc.url)
                    v.put(Calendars.OWNER_ACCOUNT, a.user)
                    v.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                    v.put(Calendars.SYNC_EVENTS, 1)
                    v.put(Calendars.VISIBLE, 1)
                    v.put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
                    v.put(Calendars.ALLOWED_REMINDERS, "${Reminders.METHOD_ALERT}")
                    val u = app.contentResolver.insert(syncUri(Calendars.CONTENT_URI, a), v) ?: continue
                    local[rc.url] = ContentUris.parseId(u)
                } else {
                    app.contentResolver.update(ContentUris.withAppendedId(syncUri(Calendars.CONTENT_URI, a), id), v, null, null)
                }
            }
            for ((url, id) in local) if (remote.none { it.url == url }) {
                app.contentResolver.delete(ContentUris.withAppendedId(syncUri(Calendars.CONTENT_URI, a), id), null, null)
                local.remove(url)
            }
            var n = 0
            for (rc in remote) local[rc.url]?.let { n += syncCalendar(a, pw, rc.url, it) }
            CalDavAccounts.setStatus(app, a.id, "Up to date · ${remote.size} calendar" + (if (remote.size == 1) "" else "s") + " · " +
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date()))
            Log.i(TAG, "${a.name}: synced, $n changes")
        } catch (e: Exception) {
            Log.w(TAG, "${a.name}: sync failed", e)
            CalDavAccounts.setStatus(app, a.id, "Not synced: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            syncing.value = syncing.value - a.id
        }
    }

    private class Local(val id: Long, val href: String?, val etag: String?, val dirty: Boolean, val deleted: Boolean)

    private fun localEvents(a: CalDavAccount, calId: Long): List<Local> {
        val out = ArrayList<Local>()
        app.contentResolver.query(syncUri(Events.CONTENT_URI, a), arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.DIRTY, Events.DELETED),
            "${Events.CALENDAR_ID}=?", arrayOf(calId.toString()), null)?.use { c ->
            while (c.moveToNext()) out += Local(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getInt(4) == 1)
        }
        return out
    }

    private fun syncCalendar(a: CalDavAccount, pw: String, calUrl: String, calId: Long): Int {
        var changes = 0
        val cr = app.contentResolver
        for (l in localEvents(a, calId).filter { it.dirty || it.deleted }) {
            val row = ContentUris.withAppendedId(syncUri(Events.CONTENT_URI, a), l.id)
            if (l.deleted) {
                if (l.href != null) {
                    val r = dav("DELETE", l.href, a.user, pw, extra = if (l.etag != null) mapOf("If-Match" to l.etag) else emptyMap())
                    if (r.code !in 200..299 && r.code != 404 && r.code != 412) { Log.w(TAG, "delete ${l.href}: ${r.code}"); continue }
                }
                cr.delete(row, null, null)
                changes++
                continue
            }
            val ev = readEvent(l.id) ?: continue
            val href = l.href ?: (calUrl.trimEnd('/') + "/" + ev.uid.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".ics")
            val r = dav("PUT", href, a.user, pw, toIcs(ev), type = "text/calendar; charset=utf-8",
                extra = if (l.href != null && l.etag != null) mapOf("If-Match" to l.etag) else if (l.href == null) mapOf("If-None-Match" to "*") else emptyMap())
            if (r.code == 412) {
                cr.update(row, ContentValues().apply { put(Events.DIRTY, 0); putNull(Events.SYNC_DATA1) }, null, null)
                continue
            }
            if (r.code !in 200..299) { Log.w(TAG, "put $href: ${r.code}"); continue }
            cr.update(row, ContentValues().apply {
                put(Events._SYNC_ID, href)
                if (r.etag != null) put(Events.SYNC_DATA1, r.etag) else putNull(Events.SYNC_DATA1)
                put(Events.SYNC_DATA2, ev.uid)
                put(Events.DIRTY, 0)
            }, null, null)
            changes++
        }
        val now = Instant.now()
        val from = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now.minus(Duration.ofDays(180)))
        val to = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now.plus(Duration.ofDays(730)))
        val q = "<?xml version=\"1.0\"?><c:calendar-query $NS><d:prop><d:getetag/></d:prop><c:filter><c:comp-filter name=\"VCALENDAR\">" +
            "<c:comp-filter name=\"VEVENT\"><c:time-range start=\"$from\" end=\"$to\"/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>"
        val r = dav("REPORT", calUrl, a.user, pw, q, "1")
        check(r, "Reading the calendar")
        val remote = parse(r.body).filter { it.props["getetag"] != null }.associate { abs(calUrl, it.href) to it.props["getetag"]!! }
        val local = localEvents(a, calId).filter { it.href != null }.associateBy { it.href!! }
        val fetch = remote.filter { (href, etag) -> local[href]?.etag != etag && local[href]?.dirty != true }.keys.toList()
        for (chunk in fetch.chunked(40)) {
            val mg = "<?xml version=\"1.0\"?><c:calendar-multiget $NS><d:prop><d:getetag/><c:calendar-data/></d:prop>" +
                chunk.joinToString("") { "<d:href>" + java.net.URI(it).rawPath + "</d:href>" } + "</c:calendar-multiget>"
            val m = dav("REPORT", calUrl, a.user, pw, mg, "1")
            check(m, "Reading events")
            for (item in parse(m.body)) {
                val data = item.props["calendar-data"] ?: continue
                val href = abs(calUrl, item.href)
                val ev = parseIcs(data) ?: continue
                upsert(a, calId, href, item.props["getetag"], ev, local[href]?.id)
                changes++
            }
        }
        for ((href, l) in local) if (href !in remote && !l.dirty) {
            cr.delete(ContentUris.withAppendedId(syncUri(Events.CONTENT_URI, a), l.id), null, null)
            changes++
        }
        return changes
    }

    class Ev(
        var uid: String = UUID.randomUUID().toString(), var title: String = "", var location: String = "", var description: String = "",
        var start: Long = 0, var end: Long = 0, var allDay: Boolean = false, var tz: String = "UTC",
        var rrule: String? = null, var exdate: String? = null, var alarm: Int? = null,
    )

    private fun upsert(a: CalDavAccount, calId: Long, href: String, etag: String?, ev: Ev, existing: Long?) {
        val cr = app.contentResolver
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, ev.title)
            put(Events.EVENT_LOCATION, ev.location)
            put(Events.DESCRIPTION, ev.description)
            put(Events.DTSTART, ev.start)
            put(Events.ALL_DAY, if (ev.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (ev.allDay) "UTC" else ev.tz)
            if (ev.rrule != null) {
                put(Events.RRULE, ev.rrule)
                putNull(Events.DTEND)
                val len = (ev.end - ev.start).coerceAtLeast(0)
                put(Events.DURATION, if (ev.allDay) "P${(len / 86_400_000L).coerceAtLeast(1)}D" else "P${len / 1000}S")
                if (ev.exdate != null) put(Events.EXDATE, ev.exdate) else putNull(Events.EXDATE)
            } else {
                putNull(Events.RRULE); putNull(Events.DURATION); putNull(Events.EXDATE)
                put(Events.DTEND, ev.end)
            }
            put(Events._SYNC_ID, href)
            if (etag != null) put(Events.SYNC_DATA1, etag)
            put(Events.SYNC_DATA2, ev.uid)
            put(Events.DIRTY, 0)
            put(Events.HAS_ALARM, if (ev.alarm != null) 1 else 0)
        }
        val id = if (existing != null) {
            cr.update(ContentUris.withAppendedId(syncUri(Events.CONTENT_URI, a), existing), v, null, null)
            existing
        } else {
            cr.insert(syncUri(Events.CONTENT_URI, a), v)?.let { ContentUris.parseId(it) } ?: return
        }
        cr.delete(syncUri(Reminders.CONTENT_URI, a), "${Reminders.EVENT_ID}=?", arrayOf(id.toString()))
        ev.alarm?.let { m ->
            cr.insert(syncUri(Reminders.CONTENT_URI, a), ContentValues().apply {
                put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, m); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
    }

    private fun readEvent(id: Long): Ev? {
        app.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id),
            arrayOf(Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
                Events.EVENT_TIMEZONE, Events.RRULE, Events.DURATION, Events.SYNC_DATA2, Events.EXDATE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val ev = Ev(
                uid = c.str(9).ifEmpty { UUID.randomUUID().toString() }, title = c.str(0), location = c.str(1), description = c.str(2),
                start = c.getLong(3), allDay = c.getInt(5) == 1, tz = c.str(6).ifEmpty { "UTC" },
                rrule = c.getString(7)?.ifEmpty { null }, exdate = c.getString(10)?.ifEmpty { null },
            )
            ev.end = if (!c.isNull(4)) c.getLong(4) else ev.start + durationMs(c.str(8))
            app.contentResolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID}=?",
                arrayOf(id.toString()), "${Reminders.MINUTES} ASC")?.use { r -> if (r.moveToFirst()) ev.alarm = r.getInt(0) }
            return ev
        }
        return null
    }

    private fun Cursor.str(i: Int): String = getString(i) ?: ""

    private fun durationMs(d: String): Long = runCatching {
        if (d.isEmpty()) return 3_600_000L
        val m = Regex("P(\\d+)W").matchEntire(d)
        if (m != null) m.groupValues[1].toLong() * 7 * 86_400_000L else Duration.parse(d).toMillis()
    }.getOrDefault(3_600_000L)

    private val utcStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val dayStamp = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun toIcs(e: Ev): String {
        val sb = StringBuilder()
        fun line(s: String) {
            var rest = s
            var first = true
            while (rest.length > 74) {
                sb.append(if (first) "" else " ").append(rest, 0, 74).append("\r\n")
                rest = rest.substring(74); first = false
            }
            sb.append(if (first) "" else " ").append(rest).append("\r\n")
        }
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
        line("BEGIN:VCALENDAR"); line("VERSION:2.0"); line("PRODID:-//JL10//BB10 Calendar//EN"); line("BEGIN:VEVENT")
        line("UID:" + e.uid)
        line("DTSTAMP:" + utcStamp.format(Instant.now()))
        if (e.allDay) {
            line("DTSTART;VALUE=DATE:" + dayStamp.format(Instant.ofEpochMilli(e.start)))
            line("DTEND;VALUE=DATE:" + dayStamp.format(Instant.ofEpochMilli(maxOf(e.end, e.start + 86_400_000L))))
        } else {
            line("DTSTART:" + utcStamp.format(Instant.ofEpochMilli(e.start)))
            line("DTEND:" + utcStamp.format(Instant.ofEpochMilli(maxOf(e.end, e.start))))
        }
        if (e.title.isNotEmpty()) line("SUMMARY:" + esc(e.title))
        if (e.location.isNotEmpty()) line("LOCATION:" + esc(e.location))
        if (e.description.isNotEmpty()) line("DESCRIPTION:" + esc(e.description))
        e.rrule?.let { line("RRULE:$it") }
        e.alarm?.let { m ->
            line("BEGIN:VALARM"); line("ACTION:DISPLAY"); line("DESCRIPTION:" + esc(e.title.ifEmpty { "Reminder" })); line("TRIGGER:-PT${m}M"); line("END:VALARM")
        }
        line("END:VEVENT"); line("END:VCALENDAR")
        return sb.toString()
    }

    fun parseIcs(ics: String): Ev? {
        val lines = ArrayList<String>()
        for (raw in ics.replace("\r\n", "\n").split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) lines[lines.size - 1] = lines.last() + raw.substring(1)
            else if (raw.isNotEmpty()) lines += raw
        }
        var ev: Ev? = null
        var inEvent = false
        var inAlarm = false
        var isOverride = false
        var cur: Ev? = null
        var hasEnd = false
        var duration: String? = null
        for (l in lines) {
            val colon = l.indexOf(':').takeIf { it > 0 } ?: continue
            val head = l.substring(0, colon)
            val value = l.substring(colon + 1)
            val name = head.substringBefore(';').uppercase()
            val params = head.split(';').drop(1).associate { p -> p.substringBefore('=').uppercase() to p.substringAfter('=').trim('"') }
            when {
                name == "BEGIN" && value == "VEVENT" -> { inEvent = true; cur = Ev(); isOverride = false; hasEnd = false; duration = null }
                name == "END" && value == "VEVENT" -> {
                    val c = cur
                    if (c != null && !isOverride) {
                        if (!hasEnd) c.end = c.start + (duration?.let { durationMs(it) } ?: if (c.allDay) 86_400_000L else 3_600_000L)
                        if (ev == null) ev = c
                    }
                    inEvent = false; cur = null
                }
                !inEvent -> {}
                name == "BEGIN" && value == "VALARM" -> inAlarm = true
                name == "END" && value == "VALARM" -> inAlarm = false
                inAlarm -> if (name == "TRIGGER" && params["VALUE"] != "DATE-TIME") cur?.alarm = (-durationMs(value) / 60_000L).toInt().coerceAtLeast(0)
                name == "UID" -> cur?.uid = value
                name == "SUMMARY" -> cur?.title = unesc(value)
                name == "LOCATION" -> cur?.location = unesc(value)
                name == "DESCRIPTION" -> cur?.description = unesc(value)
                name == "RECURRENCE-ID" -> isOverride = true
                name == "RRULE" -> cur?.rrule = value
                name == "EXDATE" -> cur?.let { c ->
                    val ms = value.split(',').mapNotNull { v -> time(v, params)?.first }
                    val s = ms.joinToString(",") { utcStamp.format(Instant.ofEpochMilli(it)) }
                    c.exdate = listOfNotNull(c.exdate, s.ifEmpty { null }).joinToString(",").ifEmpty { null }
                }
                name == "DTSTART" -> time(value, params)?.let { (t, allDay) ->
                    cur?.start = t; cur?.allDay = allDay
                    cur?.tz = if (allDay) "UTC" else params["TZID"]?.let { id -> runCatching { ZoneId.of(id).id }.getOrNull() } ?: if (value.endsWith("Z")) "UTC" else ZoneId.systemDefault().id
                }
                name == "DTEND" -> time(value, params)?.let { (t, _) -> cur?.end = t; hasEnd = true }
                name == "DURATION" -> duration = value
            }
        }
        return ev
    }

    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun time(v: String, params: Map<String, String>): Pair<Long, Boolean>? = runCatching {
        val s = v.trim()
        if (params["VALUE"] == "DATE" || (s.length == 8 && s.all { it.isDigit() })) {
            Pair(LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true)
        } else {
            val ldt = LocalDateTime.parse(s.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            val zone = when {
                s.endsWith("Z") -> ZoneOffset.UTC
                params["TZID"] != null -> runCatching { ZoneId.of(params["TZID"]) }.getOrDefault(ZoneId.systemDefault())
                else -> ZoneId.systemDefault()
            }
            Pair(ldt.atZone(zone).toInstant().toEpochMilli(), false)
        }
    }.getOrNull()
}
