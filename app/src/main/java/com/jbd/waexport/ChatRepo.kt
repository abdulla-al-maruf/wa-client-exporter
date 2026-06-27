package com.jbd.waexport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.jbd.waexport.model.Chat
import java.io.File

object ChatRepo {
    private const val TAG = "ChatRepo"

    fun getChats(context: Context): List<Chat> {
        val dbDir = File(context.filesDir, "dbs")
        val msgstore = File(dbDir, "msgstore.db")
        val wa = File(dbDir, "wa.db")

        val chats = mutableListOf<Chat>()
        if (!msgstore.exists() || msgstore.length() == 0L) {
            Log.e(TAG, "msgstore.db missing or empty")
            return chats
        }

        // Diagnostic Logging as requested
        logDiagnosticSchema(msgstore, wa)

        // 1) Build name map from wa.db: jid -> display_name (fallback to wa_name)
        val nameMap = HashMap<String, String>()
        if (wa.exists() && wa.length() > 0) {
            try {
                SQLiteDatabase.openDatabase(wa.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT jid, display_name, wa_name FROM wa_contacts", null).use { c ->
                        val ji = c.getColumnIndex("jid")
                        val ni = c.getColumnIndex("display_name")
                        val wi = c.getColumnIndex("wa_name")
                        while (c.moveToNext()) {
                            val j = c.getString(ji) ?: continue
                            val dn = if (ni >= 0) c.getString(ni) else null
                            val wn = if (wi >= 0) c.getString(wi) else null
                            if (!dn.isNullOrBlank()) nameMap[j] = dn
                            else if (!wn.isNullOrBlank()) nameMap[j] = wn
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "wa.db read failed", e)
            }
        }

        // 2) Build lid -> pn map and read chats from msgstore.db
        try {
            SQLiteDatabase.openDatabase(msgstore.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val lidMap = HashMap<String, String>()
                try {
                    db.rawQuery(
                        "SELECT j1.raw_string as lid, j2.raw_string as pn FROM jid_map JOIN jid j1 ON jid_map.lid_row_id = j1._id JOIN jid j2 ON jid_map.jid_row_id = j2._id",
                        null
                    ).use { c ->
                        val li = c.getColumnIndex("lid")
                        val pi = c.getColumnIndex("pn")
                        if (li >= 0 && pi >= 0) {
                            while (c.moveToNext()) {
                                val lid = c.getString(li) ?: continue
                                val pn = c.getString(pi) ?: continue
                                lidMap[lid] = pn
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read jid_map", e)
                }

                db.rawQuery(
                    "SELECT j.raw_string AS jid, c.subject, c.sort_timestamp FROM chat c JOIN jid j ON c.jid_row_id = j._id ORDER BY IFNULL(c.sort_timestamp, 0) DESC",
                    null
                ).use { cur ->
                    val ji = cur.getColumnIndex("jid")
                    val si = cur.getColumnIndex("subject")
                    if (ji >= 0) {
                        val ti = cur.getColumnIndex("sort_timestamp")
                        while (cur.moveToNext()) {
                            val jid = cur.getString(ji) ?: continue
                            if (jid.endsWith("@broadcast") || jid.endsWith("status@broadcast")) continue

                            var name: String? = null
                            if (jid.endsWith("@g.us")) {
                                if (si >= 0) name = cur.getString(si)
                            } else {
                                val lookupJid = if (jid.endsWith("@lid")) lidMap[jid] ?: jid else jid
                                name = nameMap[lookupJid]
                            }

                            if (name.isNullOrBlank()) {
                                name = jid.substringBefore("@")
                            }
                            val ts = if (ti >= 0 && !cur.isNull(ti)) cur.getLong(ti) else 0L
                            chats.add(Chat(jid = jid, displayName = name, timestamp = ts))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "msgstore.db read failed", e)
        }

        return chats.distinctBy { it.jid }
    }

    private fun logDiagnosticSchema(msgstore: File, wa: File) {
        try {
            Log.e(TAG, "=== SCHEMA LOGS ===")
            if (wa.exists() && wa.length() > 0) {
                SQLiteDatabase.openDatabase(wa.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("PRAGMA table_info(wa_contacts)", null).use { cur ->
                        val cols = mutableListOf<String>()
                        while (cur.moveToNext()) cols.add(cur.getString(1))
                        Log.e(TAG, "wa_contacts columns: \${cols.joinToString()}")
                    }
                    db.rawQuery("SELECT * FROM wa_contacts LIMIT 3", null).use { cur ->
                        while (cur.moveToNext()) {
                            val row = (0 until cur.columnCount).joinToString { cur.getString(it) ?: "null" }
                            Log.e(TAG, "wa_contacts row: \$row")
                        }
                    }
                }
            }
            if (msgstore.exists() && msgstore.length() > 0) {
                SQLiteDatabase.openDatabase(msgstore.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cur ->
                        val tables = mutableListOf<String>()
                        while (cur.moveToNext()) tables.add(cur.getString(0))
                        Log.e(TAG, "msgstore tables: \${tables.joinToString()}")
                    }
                    arrayOf("chat", "jid").forEach { table ->
                        db.rawQuery("PRAGMA table_info(\$table)", null).use { cur ->
                            val cols = mutableListOf<String>()
                            while (cur.moveToNext()) cols.add(cur.getString(1))
                            Log.e(TAG, "\$table columns: \${cols.joinToString()}")
                        }
                        db.rawQuery("SELECT * FROM \$table LIMIT 3", null).use { cur ->
                            while (cur.moveToNext()) {
                                val row = (0 until cur.columnCount).joinToString { cur.getString(it) ?: "null" }
                                Log.e(TAG, "\$table row: \$row")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed diagnostic logs", e)
        }
    }
}
