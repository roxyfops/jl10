package com.bb10.launcher.mail

import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class ServerConfig(
    val imapHost: String, val imapPort: Int, val imapSec: Security,
    val smtpHost: String, val smtpPort: Int, val smtpSec: Security,
    val userIsLocalPart: Boolean = false,
    val note: String? = null,
)

object MailConfig {
    private const val APP_PW = "needs an app password (made in your account's security settings), not your normal password"

    private val known: Map<String, ServerConfig> = buildMap {
        val gmail = ServerConfig("imap.gmail.com", 993, Security.SSL, "smtp.gmail.com", 465, Security.SSL,
            note = "Gmail $APP_PW: myaccount.google.com/apppasswords (2-step verification must be on)")
        listOf("gmail.com", "googlemail.com").forEach { put(it, gmail) }
        val ms = ServerConfig("outlook.office365.com", 993, Security.SSL, "smtp.office365.com", 587, Security.STARTTLS,
            note = "Outlook / Hotmail $APP_PW, if 2-step verification is on: account.microsoft.com/security")
        listOf("outlook.com", "hotmail.com", "live.com", "msn.com", "hotmail.co.uk", "outlook.de", "hotmail.de").forEach { put(it, ms) }
        val yahoo = ServerConfig("imap.mail.yahoo.com", 993, Security.SSL, "smtp.mail.yahoo.com", 465, Security.SSL,
            note = "Yahoo $APP_PW: login.yahoo.com/account/security")
        listOf("yahoo.com", "ymail.com", "yahoo.co.uk", "yahoo.de", "yahoo.fr", "rocketmail.com").forEach { put(it, yahoo) }
        val icloud = ServerConfig("imap.mail.me.com", 993, Security.SSL, "smtp.mail.me.com", 587, Security.STARTTLS,
            userIsLocalPart = false, note = "iCloud $APP_PW: account.apple.com, Sign-In and Security")
        listOf("icloud.com", "me.com", "mac.com").forEach { put(it, icloud) }
        put("aol.com", ServerConfig("imap.aol.com", 993, Security.SSL, "smtp.aol.com", 465, Security.SSL, note = "AOL $APP_PW"))
        val gmx = ServerConfig("imap.gmx.net", 993, Security.SSL, "mail.gmx.net", 587, Security.STARTTLS)
        listOf("gmx.com", "gmx.net", "gmx.de", "gmx.at").forEach { put(it, gmx) }
        listOf("yandex.com", "yandex.ru", "ya.ru").forEach {
            put(it, ServerConfig("imap.yandex.com", 993, Security.SSL, "smtp.yandex.com", 465, Security.SSL, note = "Yandex $APP_PW"))
        }
        put("zoho.com", ServerConfig("imap.zoho.com", 993, Security.SSL, "smtp.zoho.com", 465, Security.SSL))
        put("mail.com", ServerConfig("imap.mail.com", 993, Security.SSL, "smtp.mail.com", 587, Security.STARTTLS))
    }

    fun find(email: String): ServerConfig {
        val domain = email.substringAfter('@', "").lowercase().trim()
        known[domain]?.let { return it }
        ispdb(domain)?.let { return it }
        return ServerConfig("imap.$domain", 993, Security.SSL, "smtp.$domain", 465, Security.SSL)
    }

    private fun ispdb(domain: String): ServerConfig? = runCatching {
        val c = URL("https://autoconfig.thunderbird.net/v1.1/$domain").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (c.responseCode != 200) return null
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(c.inputStream)
            fun first(tag: String, type: String): Element? {
                val l = doc.getElementsByTagName(tag)
                return (0 until l.length).map { l.item(it) as Element }.firstOrNull { it.getAttribute("type") == type }
            }
            val inc = first("incomingServer", "imap") ?: return null
            val out = first("outgoingServer", "smtp") ?: return null
            fun text(e: Element, t: String) = e.getElementsByTagName(t).item(0)?.textContent?.trim().orEmpty()
            fun sec(e: Element) = when (text(e, "socketType").uppercase()) {
                "SSL" -> Security.SSL
                "STARTTLS" -> Security.STARTTLS
                else -> Security.NONE
            }
            ServerConfig(
                text(inc, "hostname"), text(inc, "port").toIntOrNull() ?: 993, sec(inc),
                text(out, "hostname"), text(out, "port").toIntOrNull() ?: 465, sec(out),
                userIsLocalPart = text(inc, "username") == "%EMAILLOCALPART%",
            )
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    fun note(email: String): String? = known[email.substringAfter('@', "").lowercase().trim()]?.note
}
