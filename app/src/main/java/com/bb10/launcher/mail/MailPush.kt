package com.bb10.launcher.mail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import javax.mail.Folder
import javax.mail.Store
import javax.mail.event.MessageChangedEvent
import javax.mail.event.MessageChangedListener
import javax.mail.event.MessageCountEvent
import javax.mail.event.MessageCountListener

object MailPush {
    private const val TAG = "BB10MailPush"

    const val READ_TIMEOUT_MS = 20 * 60_000

    private const val KEEPALIVE_MS = 7 * 60_000L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val pushers = HashMap<String, Pusher>()
    private var started = false

    fun start(c: Context) {
        if (started) return
        started = true
        app = c.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) {
                val changed = current != null && current != network
                current = network
                main.post { if (changed) reconnectAll() else wakeAll() }
            }

            override fun onLost(network: Network) {
                if (network == current) current = null
            }
        })
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pm = app.getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isDeviceIdleMode) reconnectAll()
            }
        }, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
        refresh()
        nudgeLater()
    }

    private val nudge = android.app.AlarmManager.OnAlarmListener {
        if (pushers.isNotEmpty()) {
            wakeLock.acquire(15_000L)
            pushers.values.forEach { it.nudge() }
        }
        nudgeLater()
    }

    private fun nudgeLater() {
        val am = app.getSystemService(android.app.AlarmManager::class.java) ?: return
        am.cancel(nudge)
        am.setWindow(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + KEEPALIVE_MS,
            60_000L, "JL10:mailpush", nudge, main)
    }

    fun refresh() {
        if (!started) return
        main.postDelayed({
            val list = MailAccounts.list(app)
            val ids = list.map { it.id }.toSet()
            pushers.keys.filter { it !in ids }.forEach { pushers.remove(it)?.stop() }
            for (a in list) {
                val p = pushers[a.id]
                if (p != null && p.account == a) continue
                p?.stop()
                pushers[a.id] = Pusher(a).also { it.begin() }
            }
        }, 500)
    }

    private fun reconnectAll() = pushers.values.forEach { it.reconnect() }

    private fun wakeAll() = pushers.values.forEach { it.wake() }

    private fun online(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private val wakeLock by lazy {
        app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JL10:mailpush")
            .apply { setReferenceCounted(false) }
    }

    private class Pusher(val account: MailAccount) {
        @Volatile private var running = true
        @Volatile private var store: Store? = null
        @Volatile private var folder: IMAPFolder? = null
        private val lock = Object()
        private var failures = 0
        private val thread = Thread({ loop() }, "mailpush-" + account.id.hashCode())

        fun begin() {
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            running = false
            drop()
            wake()
        }

        fun reconnect() {
            drop()
            wake()
        }

        fun wake() = synchronized(lock) { lock.notifyAll() }

        private fun drop() {
            val s = store
            if (s != null) Thread { runCatching { s.close() } }.start()
        }

        private fun pause(ms: Long) = synchronized(lock) {
            if (running) runCatching { lock.wait(ms) }
        }

        private fun loop() {
            while (running) {
                if (!online()) {
                    pause(15 * 60_000L)
                    continue
                }
                try {
                    session()
                    failures = 0
                } catch (e: Throwable) {
                    if (!running) break
                    if (e is NoIdle) {
                        Log.i(TAG, "${account.id}: the server has no IDLE; the 15-minute sync covers it")
                        return
                    }
                    failures++
                    Log.i(TAG, "${account.id}: push connection dropped ($e); again shortly")
                } finally {
                    folder = null
                    store?.let { s -> runCatching { s.close() } }
                    store = null
                }
                if (!running) break
                val back = if (failures == 0) 2_000L else minOf(15 * 60_000L, 10_000L * Math.pow(3.0, (failures - 1).toDouble()).toLong())
                pause(back)
            }
        }

        private class NoIdle : Exception()

        private fun session() {
            val s = Mail.idleStore(account)
            store = s
            if (s is IMAPStore && !s.hasCapability("IDLE")) throw NoIdle()
            val f = s.getFolder("INBOX") as IMAPFolder
            f.open(Folder.READ_ONLY)
            folder = f
            val changed = java.util.concurrent.atomic.AtomicBoolean(false)
            f.addMessageCountListener(object : MessageCountListener {
                override fun messagesAdded(e: MessageCountEvent) = changed.set(true)
                override fun messagesRemoved(e: MessageCountEvent) = changed.set(true)
            })
            f.addMessageChangedListener(MessageChangedListener { _: MessageChangedEvent -> changed.set(true) })
            synced()
            while (running && f.isOpen) {
                f.idle(true)
                if (changed.getAndSet(false)) synced()
            }
        }

        fun nudge() {
            val f = folder ?: return
            Thread { runCatching { f.doCommand { p -> p.simpleCommand("NOOP", null); null } } }.start()
        }

        private fun synced() {
            wakeLock.acquire(60_000L)
            main.post { Mail.pushed(account) }
        }
    }
}
