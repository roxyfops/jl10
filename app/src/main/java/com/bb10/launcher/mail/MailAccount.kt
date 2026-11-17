package com.bb10.launcher.mail

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bb10.launcher.hub.HubStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Security { SSL, STARTTLS, NONE }

data class MailAccount(
    val email: String,
    val name: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSec: Security,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSec: Security,
    val user: String,
    val color: Int,
) {
    val id: String get() = email.lowercase()

    val pkg: String get() = HubStore.MAIL + id

    fun toJson(): JSONObject = JSONObject()
        .put("email", email).put("name", name)
        .put("imapHost", imapHost).put("imapPort", imapPort).put("imapSec", imapSec.name)
        .put("smtpHost", smtpHost).put("smtpPort", smtpPort).put("smtpSec", smtpSec.name)
        .put("user", user).put("color", color)

    companion object {
        fun fromJson(o: JSONObject) = MailAccount(
            o.getString("email"), o.optString("name"),
            o.getString("imapHost"), o.getInt("imapPort"), Security.valueOf(o.optString("imapSec", "SSL")),
            o.getString("smtpHost"), o.getInt("smtpPort"), Security.valueOf(o.optString("smtpSec", "SSL")),
            o.optString("user", o.getString("email")), o.optInt("color", 0xFF0092CC.toInt()),
        )

        val COLORS = intArrayOf(0xFF0092CC.toInt(), 0xFFE67E22.toInt(), 0xFF6BAA2B.toInt(), 0xFF8E5BC4.toInt(),
            0xFFD9363E.toInt(), 0xFF00A38F.toInt())
    }
}

object MailAccounts {
    private const val PREFS = "mail_accounts"
    private const val KEY_ALIAS = "jl10_mail"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(c: Context): List<MailAccount> = runCatching {
        val a = JSONArray(prefs(c).getString("accounts", "[]"))
        (0 until a.length()).map { MailAccount.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun byId(c: Context, id: String): MailAccount? = list(c).firstOrNull { it.id == id }

    fun byPkg(c: Context, pkg: String): MailAccount? = list(c).firstOrNull { it.pkg == pkg }

    fun save(c: Context, acct: MailAccount, password: String?) {
        val all = list(c).filter { it.id != acct.id } + acct
        val a = JSONArray()
        all.forEach { a.put(it.toJson()) }
        val e = prefs(c).edit().putString("accounts", a.toString())
        if (password != null) e.putString("pw_" + acct.id, encrypt(password))
        e.apply()
    }

    fun remove(c: Context, id: String) {
        val a = JSONArray()
        list(c).filter { it.id != id }.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("accounts", a.toString()).remove("pw_$id").apply()
    }

    fun password(c: Context, acct: MailAccount): String? =
        prefs(c).getString("pw_" + acct.id, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return g.generateKey()
    }

    internal fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(s.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    internal fun decrypt(s: String): String {
        val all = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }
}
