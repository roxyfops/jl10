package com.bb10.launcher.mail

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class MailMsg(
    val acct: String,
    val uid: Long,
    val msgId: String,
    val refs: String,
    val fromName: String,
    val fromAddr: String,
    val to: String,
    val cc: String,
    val replyTo: String,
    val subject: String,
    val snippet: String,
    val date: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val attach: Boolean,
) {
    val sender: String get() = fromName.ifBlank { fromAddr }

    val hubId: String get() = itemId(acct, uid)

    companion object {
        fun itemId(acct: String, uid: Long) = "mail:$acct:$uid"

        fun parse(id: String): Pair<String, Long>? {
            if (!id.startsWith("mail:")) return null
            val rest = id.removePrefix("mail:")
            val cut = rest.lastIndexOf(':')
            if (cut <= 0) return null
            return rest.substring(0, cut) to (rest.substring(cut + 1).toLongOrNull() ?: return null)
        }
    }
}

class MailDb(c: Context) : SQLiteOpenHelper(c, "mail.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE msg(acct TEXT, uid INTEGER, msgid TEXT, refs TEXT, fromName TEXT, fromAddr TEXT," +
            " toAddrs TEXT, ccAddrs TEXT, replyTo TEXT, subject TEXT, snippet TEXT, date INTEGER, seen INTEGER," +
            " flagged INTEGER, attach INTEGER, PRIMARY KEY(acct, uid))")
        db.execSQL("CREATE TABLE box(acct TEXT PRIMARY KEY, validity INTEGER, lastUid INTEGER, synced INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun messages(acct: String, limit: Int = 300): List<MailMsg> = readableDatabase.query("msg", null, "acct=?", arrayOf(acct),
        null, null, "uid DESC", limit.toString()).use { c ->
        val out = ArrayList<MailMsg>()
        while (c.moveToNext()) out += row(c)
        out
    }

    fun message(acct: String, uid: Long): MailMsg? = readableDatabase.query("msg", null, "acct=? AND uid=?",
        arrayOf(acct, uid.toString()), null, null, null).use { c -> if (c.moveToFirst()) row(c) else null }

    private fun row(c: android.database.Cursor) = MailMsg(
        c.getString(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "",
        c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: "", c.getString(8) ?: "", c.getString(9) ?: "",
        c.getString(10) ?: "", c.getLong(11), c.getInt(12) == 1, c.getInt(13) == 1, c.getInt(14) == 1,
    )

    fun put(m: MailMsg) {
        writableDatabase.insertWithOnConflict("msg", null, ContentValues().apply {
            put("acct", m.acct); put("uid", m.uid); put("msgid", m.msgId); put("refs", m.refs)
            put("fromName", m.fromName); put("fromAddr", m.fromAddr); put("toAddrs", m.to); put("ccAddrs", m.cc)
            put("replyTo", m.replyTo); put("subject", m.subject); put("snippet", m.snippet); put("date", m.date)
            put("seen", if (m.seen) 1 else 0); put("flagged", if (m.flagged) 1 else 0); put("attach", if (m.attach) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setSeen(acct: String, uid: Long, seen: Boolean) {
        writableDatabase.update("msg", ContentValues().apply { put("seen", if (seen) 1 else 0) }, "acct=? AND uid=?",
            arrayOf(acct, uid.toString()))
    }

    fun delete(acct: String, uid: Long) {
        writableDatabase.delete("msg", "acct=? AND uid=?", arrayOf(acct, uid.toString()))
    }

    fun trim(acct: String, keep: Int) {
        writableDatabase.execSQL("DELETE FROM msg WHERE acct=? AND uid NOT IN (SELECT uid FROM msg WHERE acct=? ORDER BY uid DESC LIMIT $keep)",
            arrayOf(acct, acct))
    }

    fun forget(acct: String) {
        writableDatabase.delete("msg", "acct=?", arrayOf(acct))
        writableDatabase.delete("box", "acct=?", arrayOf(acct))
    }

    fun box(acct: String): Pair<Long, Long>? = readableDatabase.query("box", arrayOf("validity", "lastUid"), "acct=?",
        arrayOf(acct), null, null, null).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }

    fun setBox(acct: String, validity: Long, lastUid: Long) {
        writableDatabase.insertWithOnConflict("box", null, ContentValues().apply {
            put("acct", acct); put("validity", validity); put("lastUid", lastUid); put("synced", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
