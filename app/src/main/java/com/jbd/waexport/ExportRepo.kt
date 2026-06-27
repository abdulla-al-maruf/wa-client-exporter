package com.jbd.waexport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.jbd.waexport.model.Chat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportMode { FULL, RECENT_7D }

data class ExportStats(
    val messagesRead: Int,
    val newMediaCopied: Int,
    val existingMediaSkipped: Int,
    val bundleType: String,
    val bundleSize: Long,
    val bundleFile: File
)

object ExportRepo {
    private const val TAG = "ExportRepo"

    /**
     * Stable export directory keyed by JID user-part (phone number / group ID).
     * Creates folder + media/ + bundles/, writes meta.json, migrates old name-based folders.
     */
    fun getExportDir(context: Context, chat: Chat): File {
        val exportRoot = File(context.getExternalFilesDir(null), "exports")
        if (!exportRoot.exists()) exportRoot.mkdirs()

        val folderId = chat.jid.substringBefore("@").replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val exportDir = File(exportRoot, folderId)

        // Migration: rename old name-based folder to jid-based
        if (!exportDir.exists()) {
            val oldSafeName = chat.displayName?.replace(Regex("[^a-zA-Z0-9.-]"), "_") ?: folderId
            if (oldSafeName != folderId) {
                val oldDir = File(exportRoot, oldSafeName)
                if (oldDir.exists() && oldDir.isDirectory) {
                    oldDir.renameTo(exportDir)
                    Log.d(TAG, "Migrated folder: ${oldDir.name} → ${exportDir.name}")
                }
            }
        }

        if (!exportDir.exists()) exportDir.mkdirs()
        File(exportDir, "media").apply { if (!exists()) mkdirs() }
        File(exportDir, "bundles").apply { if (!exists()) mkdirs() }

        try {
            val meta = JSONObject()
            meta.put("displayName", chat.displayName ?: folderId)
            meta.put("jid", chat.jid)
            meta.put("lastExported", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            File(exportDir, "meta.json").writeText(meta.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write meta.json", e)
        }

        return exportDir
    }

    /**
     * Main export entry point.
     * 1. Updates the master folder incrementally (messages + media).
     * 2. Builds a shareable bundle in bundles/ based on [mode].
     */
    fun exportChat(context: Context, chat: Chat, mode: ExportMode, onProgress: ((String, Float) -> Unit)? = null): ExportStats? {
        val dbDir = File(context.filesDir, "dbs")
        val msgstore = File(dbDir, "msgstore.db")
        val wa = File(dbDir, "wa.db")

        if (!msgstore.exists() || !wa.exists()) {
            Log.e(TAG, "DBs missing")
            return null
        }

        val exportDir = getExportDir(context, chat)
        val mediaDir = File(exportDir, "media")

        // --- Build name maps ---
        val nameMap = HashMap<String, String>()
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
            Log.e(TAG, "wa.db mapping failed", e)
        }

        val jidRowIdToName = HashMap<Long, String>()
        val jidRowIdToRaw = HashMap<Long, String>()
        var chatRowId = -1L

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

        val transcriptFile = File(exportDir, "transcript.txt")
        val jsonFile = File(exportDir, "messages.json")
        val newSinceLastFile = File(exportDir, "new_since_last.txt")
        
        var maxTimestampRaw = 0L
        if (jsonFile.exists()) {
            try {
                val existingJson = JSONArray(jsonFile.readText())
                if (existingJson.length() > 0) {
                    maxTimestampRaw = existingJson.getJSONObject(existingJson.length() - 1).getLong("timestamp_raw")
                }
            } catch (e: Exception) {}
        }

        val jsonArray = JSONArray()
        val transcriptTmp = File(exportDir, "transcript.tmp")
        val jsonTmp = File(exportDir, "messages.tmp")
        val newSinceLastTmp = File(exportDir, "new_since_last.tmp")

        var newMediaCopied = 0
        var existingMediaSkipped = 0
        var messagesRead = 0
        // Track all media filenames referenced by messages (for recent-7d filtering)
        val allMediaFiles = mutableListOf<Pair<Long, String>>() // timestamp -> filename

        try {
            SQLiteDatabase.openDatabase(msgstore.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val lidMap = HashMap<String, String>()
                db.rawQuery("SELECT j1.raw_string as lid, j2.raw_string as pn FROM jid_map JOIN jid j1 ON jid_map.lid_row_id = j1._id JOIN jid j2 ON jid_map.jid_row_id = j2._id", null).use { c ->
                    while (c.moveToNext()) {
                        lidMap[c.getString(0)] = c.getString(1)
                    }
                }

                db.rawQuery("SELECT _id, raw_string FROM jid", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val raw = c.getString(1) ?: continue
                        jidRowIdToRaw[id] = raw

                        if (raw.endsWith("@g.us")) {
                            db.rawQuery("SELECT subject FROM chat WHERE jid_row_id = ?", arrayOf(id.toString())).use { sc ->
                                if (sc.moveToFirst()) {
                                    val sub = sc.getString(0)
                                    if (!sub.isNullOrBlank()) jidRowIdToName[id] = sub
                                }
                            }
                        } else {
                            val lookup = if (raw.endsWith("@lid")) lidMap[raw] ?: raw else raw
                            val nm = nameMap[lookup]
                            if (!nm.isNullOrBlank()) {
                                jidRowIdToName[id] = nm
                            } else {
                                jidRowIdToName[id] = raw.substringBefore("@")
                            }
                        }
                    }
                }

                db.rawQuery("SELECT c._id FROM chat c JOIN jid j ON c.jid_row_id = j._id WHERE j.raw_string = ?", arrayOf(chat.jid)).use { c ->
                    if (c.moveToFirst()) {
                        chatRowId = c.getLong(0)
                    }
                }

                if (chatRowId == -1L) {
                    Log.e(TAG, "Could not find chat_row_id for ${chat.jid}")
                    return null
                }

                // Load Reactions
                val reactionsMap = HashMap<Long, MutableList<JSONObject>>()
                val rSql = """
                    SELECT mao.parent_message_row_id, r.reaction, mao.from_me, mao.sender_jid_row_id 
                    FROM message_add_on_reaction r 
                    JOIN message_add_on mao ON r.message_add_on_row_id = mao._id 
                    WHERE mao.chat_row_id = ? AND r.reaction IS NOT NULL AND r.reaction != ''
                """.trimIndent()
                db.rawQuery(rSql, arrayOf(chatRowId.toString())).use { rc ->
                    while (rc.moveToNext()) {
                        val parentId = rc.getLong(0)
                        val reaction = rc.getString(1)
                        val rFromMe = rc.getInt(2) == 1
                        val rSenderId = if (rc.isNull(3)) -1L else rc.getLong(3)
                        val rSenderName = if (rFromMe) "Me" else (jidRowIdToName[rSenderId] ?: "Client")
                        val rObj = JSONObject()
                        rObj.put("emoji", reaction)
                        rObj.put("by", rSenderName)
                        reactionsMap.getOrPut(parentId) { mutableListOf() }.add(rObj)
                    }
                }

                // --- Read ALL messages into master transcript + json ---
                PrintWriter(transcriptTmp.bufferedWriter()).use { txt ->
                    PrintWriter(newSinceLastTmp.bufferedWriter()).use { newTxt ->
                        val sql = """
                            SELECT 
                                m._id, m.timestamp, m.from_me, m.message_type, m.text_data, m.sender_jid_row_id,
                                mm.file_path, mm.mime_type, mm.media_duration, mm.media_caption, mm.file_size, mm.raw_transcription_text,
                                mq.text_data AS q_text, mq.sender_jid_row_id AS q_sender_id, mq.message_type AS q_type
                            FROM message m
                            LEFT JOIN message_media mm ON m._id = mm.message_row_id
                            LEFT JOIN message_quoted mq ON m._id = mq.message_row_id
                            WHERE m.chat_row_id = ?
                            ORDER BY m.timestamp ASC
                        """.trimIndent()
    
                        var totalMessages = 0
                        db.rawQuery("SELECT COUNT(*) FROM message WHERE chat_row_id = ?", arrayOf(chatRowId.toString())).use { cur ->
                            if (cur.moveToFirst()) totalMessages = cur.getInt(0)
                        }
    
                        db.rawQuery(sql, arrayOf(chatRowId.toString())).use { cur ->
                            val iId = cur.getColumnIndex("_id")
                            val iTs = cur.getColumnIndex("timestamp")
                            val iFromMe = cur.getColumnIndex("from_me")
                            val iType = cur.getColumnIndex("message_type")
                            val iText = cur.getColumnIndex("text_data")
                            val iSenderId = cur.getColumnIndex("sender_jid_row_id")
                            val iFilePath = cur.getColumnIndex("file_path")
                            val iMime = cur.getColumnIndex("mime_type")
                            val iDuration = cur.getColumnIndex("media_duration")
                            val iCaption = cur.getColumnIndex("media_caption")
                            val iTranscription = cur.getColumnIndex("raw_transcription_text")
                            val iFileSize = cur.getColumnIndex("file_size")
                            val iQText = cur.getColumnIndex("q_text")
                            val iQSenderId = cur.getColumnIndex("q_sender_id")
                            val iQType = cur.getColumnIndex("q_type")
    
                            var index = 0
                            while (cur.moveToNext()) {
                                index++
                                messagesRead = index
                                if (totalMessages > 0 && index % 50 == 0) {
                                    onProgress?.invoke("Reading messages ($index / $totalMessages)", index.toFloat() / totalMessages * 0.8f)
                                }
                                val mId = cur.getLong(iId)
                                val ts = cur.getLong(iTs)
                                val fromMe = cur.getInt(iFromMe) == 1
                                val type = cur.getInt(iType)
                                var text = cur.getString(iText)
                                val senderId = if (cur.isNull(iSenderId)) -1L else cur.getLong(iSenderId)
    
                                val filePath = cur.getString(iFilePath)
                                val mime = cur.getString(iMime)
                                val duration = if (cur.isNull(iDuration)) null else cur.getInt(iDuration)
                                val caption = cur.getString(iCaption)
                                val transcription = if (iTranscription >= 0 && !cur.isNull(iTranscription)) cur.getString(iTranscription) else null
    
                                val qText = cur.getString(iQText)
                                val qSenderId = if (cur.isNull(iQSenderId)) -1L else cur.getLong(iQSenderId)
                                val qType = if (cur.isNull(iQType)) -1 else cur.getInt(iQType)
    
                                val direction = if (fromMe) "me" else "client"
                                val senderName = if (fromMe) "Me" else (jidRowIdToName[senderId] ?: chat.displayName ?: "Client")
    
                                if (type == 15) text = "[deleted]"
    
                                val readableTime = dateFormat.format(Date(ts))
                                val isoTime = isoFormat.format(Date(ts))
    
                                val jsonObj = JSONObject()
                                jsonObj.put("index", index)
                                jsonObj.put("timestamp_raw", ts)
                                jsonObj.put("timestamp_readable", readableTime)
                                jsonObj.put("timestamp_iso", isoTime)
                                jsonObj.put("direction", direction)
                                jsonObj.put("sender_name", senderName)
                                jsonObj.put("type", type)
    
                                if (type == 7 || type == 28 || type == 46) {
                                    jsonObj.put("type_name", "system")
                                }
    
                                var transcriptBlock = "[$readableTime] $senderName: "
    
                                // Quote
                                var qBlock = ""
                                if (qText != null || qType != -1) {
                                    val qObj = JSONObject()
                                    val resolvedQSender = if (qSenderId == -1L || jidRowIdToRaw[qSenderId]?.contains("s.whatsapp.net") == false) {
                                        "Unknown"
                                    } else {
                                        jidRowIdToName[qSenderId] ?: "Unknown"
                                    }
                                    val finalQSender = if (resolvedQSender == "Unknown") "Me" else resolvedQSender
    
                                    qObj.put("sender", finalQSender)
                                    qObj.put("text", qText ?: "")
                                    jsonObj.put("reply_to", qObj)
                                    qBlock = "\n↳ replying to ($finalQSender): \"${qText?.replace("\n", " ") ?: "[media]"}\""
                                } else {
                                    jsonObj.put("reply_to", JSONObject.NULL)
                                }
    
                                // Media — copy incrementally to master media/
                                var mediaBlock = ""
                                if (filePath != null) {
                                    val mObj = JSONObject()
                                    val kind = when (type) {
                                        1 -> "image"; 2 -> "voice"; 3 -> "video"
                                        9 -> "document"; 20 -> "sticker"; else -> "media"
                                    }
                                    mObj.put("kind", kind)
                                    val fileName = File(filePath).name
                                    mObj.put("file", "media/$fileName")
                                    mObj.put("mime", mime ?: "")
                                    if (duration != null) mObj.put("duration_seconds", duration)
                                    mObj.put("caption", caption ?: "")
                                    if (!transcription.isNullOrBlank()) mObj.put("transcript", transcription)
                                    jsonObj.put("media", mObj)
    
                                    // Track for bundle filtering
                                    allMediaFiles.add(ts to fileName)
    
                                    val srcPath = "/data/media/0/Android/media/com.whatsapp/WhatsApp/$filePath"
                                    val destFile = File(mediaDir, fileName)
                                    var missing = false
    
                                    if (!destFile.exists() || destFile.length() == 0L) {
                                        onProgress?.invoke("Copying media ($index / $totalMessages)", index.toFloat() / totalMessages * 0.8f)
                                        try {
                                            val p = Runtime.getRuntime().exec(arrayOf("su", "--mount-master", "-c", "cat '$srcPath'"))
                                            FileOutputStream(destFile).use { out -> p.inputStream.copyTo(out) }
                                            p.waitFor()
                                            if (destFile.length() == 0L) { destFile.delete(); missing = true }
                                            else newMediaCopied++
                                        } catch (e: Exception) { missing = true }
                                    } else {
                                        existingMediaSkipped++
                                    }
    
                                    val durStr = if (duration != null) ", ${duration / 60}:${String.format("%02d", duration % 60)}" else ""
                                    val mNote = if (missing) " [media missing]" else ""
                                    mediaBlock = "\n[$kind: media/$fileName$durStr]$mNote"
                                    if (!caption.isNullOrBlank()) mediaBlock += "\nCaption: $caption"
                                    if (!transcription.isNullOrBlank()) mediaBlock += "\n  → \"$transcription\""
                                } else {
                                    jsonObj.put("media", JSONObject.NULL)
                                }
    
                                jsonObj.put("text", text ?: "")
    
                                // Reactions
                                val rList = reactionsMap[mId]
                                if (rList != null) {
                                    val rArr = JSONArray()
                                    rList.forEach { rArr.put(it) }
                                    jsonObj.put("reactions", rArr)
                                }
    
                                jsonArray.put(jsonObj)
    
                                if (qBlock.isNotEmpty()) transcriptBlock += qBlock
                                if (mediaBlock.isNotEmpty()) transcriptBlock += mediaBlock
                                if (!text.isNullOrBlank()) transcriptBlock += if (qBlock.isEmpty() && mediaBlock.isEmpty()) text else "\n$text"
                                if (type == 5) transcriptBlock += "\n[location]"
                                if (type == 4) transcriptBlock += "\n[contact]"
    
                                if (rList != null) {
                                    rList.forEach { r ->
                                        transcriptBlock += "\n  [reaction: ${r.getString("emoji")} by ${r.getString("by")}]"
                                    }
                                }
    
                                txt.println(transcriptBlock)
                                txt.println()

                                if (ts > maxTimestampRaw) {
                                    newTxt.println(transcriptBlock)
                                    newTxt.println()
                                }
                            }
                        }
                    }
                }
            }

            onProgress?.invoke("Building files...", 0.85f)
            jsonTmp.writeText(jsonArray.toString(2))

            // Atomic renames for master files
            if (transcriptFile.exists()) transcriptFile.delete()
            transcriptTmp.renameTo(transcriptFile)
            if (jsonFile.exists()) jsonFile.delete()
            jsonTmp.renameTo(jsonFile)
            if (newSinceLastFile.exists()) newSinceLastFile.delete()
            newSinceLastTmp.renameTo(newSinceLastFile)

            // --- Build shareable bundle based on mode ---
            onProgress?.invoke("Building bundle...", 0.90f)
            val bundleFile = buildBundle(exportDir, mode, chat, allMediaFiles, onProgress)
                ?: return null

            val stats = ExportStats(
                messagesRead = messagesRead,
                newMediaCopied = newMediaCopied,
                existingMediaSkipped = existingMediaSkipped,
                bundleType = if (mode == ExportMode.FULL) "full" else "update",
                bundleSize = bundleFile.length(),
                bundleFile = bundleFile
            )

            Log.i(TAG, "Export done: ${stats.messagesRead} msgs, ${stats.newMediaCopied} new media, ${stats.existingMediaSkipped} skipped, ${stats.bundleType} ${stats.bundleSize / 1024}KB")

            return stats
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            transcriptTmp.delete()
            jsonTmp.delete()
            newSinceLastTmp.delete()
            return null
        }
    }

    // ─── Bundle builders ───────────────────────────────────────────────

    private fun buildBundle(
        exportDir: File, mode: ExportMode, chat: Chat,
        allMedia: List<Pair<Long, String>>, onProgress: ((String, Float) -> Unit)?
    ): File? {
        val bundlesDir = File(exportDir, "bundles")
        if (!bundlesDir.exists()) bundlesDir.mkdirs()
        
        val safeDisplayName = (chat.displayName ?: chat.jid.substringBefore("@")).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        
        return when (mode) {
            ExportMode.FULL -> {
                val zipFile = File(bundlesDir, "$safeDisplayName-full.zip")
                if (zipFile.exists()) zipFile.delete()
                buildZipFromContent(zipFile, exportDir, allMedia.map { it.second }.toSet(), onProgress)
            }
            ExportMode.RECENT_7D -> {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                // Filter media to only files referenced by recent messages
                val recentMediaNames = allMedia.filter { it.first >= cutoff }.map { it.second }.toSet()

                val zipFile = File(bundlesDir, "$safeDisplayName-update.zip")
                if (zipFile.exists()) zipFile.delete()
                buildZipFromContent(zipFile, exportDir, recentMediaNames, onProgress)
            }
        }
    }

    /**
     * Builds a zip containing the full messages.json, transcript.txt, new_since_last.txt, 
     * and only the referenced media based on the mode.
     */
    private fun buildZipFromContent(
        zipFile: File, exportDir: File,
        mediaNames: Set<String>, onProgress: ((String, Float) -> Unit)?
    ): File? {
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zout ->
                
                // Add the full text files
                val filesToAdd = listOf("messages.json", "transcript.txt", "new_since_last.txt")
                filesToAdd.forEach { fileName ->
                    val f = File(exportDir, fileName)
                    if (f.exists()) {
                        addToZip(zout, fileName, f)
                    }
                }

                // Add referenced media files
                val mediaDir = File(exportDir, "media")
                val mediaList = mediaNames.mapNotNull { name ->
                    val f = File(mediaDir, name)
                    if (f.exists() && f.length() > 0) f else null
                }
                val total = mediaList.size
                mediaList.forEachIndexed { idx, file ->
                    onProgress?.invoke("Bundling media ($idx / $total)", 0.90f + (idx.toFloat() / total * 0.1f))
                    addToZip(zout, "media/${file.name}", file)
                }
            }
            return zipFile
        } catch (e: Exception) {
            Log.e(TAG, "Bundle build failed", e)
            return null
        }
    }

    // ─── Zip helpers ───────────────────────────────────────────────────

    /** Extensions that are already compressed — store without deflation. */
    private val STORED_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif",
        "mp4", "3gp", "mkv", "avi",
        "opus", "ogg", "aac", "amr", "mp3",
        "pdf", "doc", "docx", "xls", "xlsx", "zip"
    )

    /** Write a single file into [zout], STORED for media, DEFLATED for text. */
    private fun addToZip(zout: ZipOutputStream, entryName: String, file: File) {
        val ext = file.extension.lowercase()
        val entry = ZipEntry(entryName)

        if (ext in STORED_EXTENSIONS) {
            entry.method = ZipEntry.STORED
            entry.size = file.length()
            entry.compressedSize = file.length()
            val crc = CRC32()
            file.inputStream().use { inp ->
                val buf = ByteArray(8192)
                var n: Int
                while (inp.read(buf).also { n = it } != -1) { crc.update(buf, 0, n) }
            }
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }

        zout.putNextEntry(entry)
        file.inputStream().use { it.copyTo(zout) }
        zout.closeEntry()
    }
}
