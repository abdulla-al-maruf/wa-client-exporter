package com.jbd.waexport

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

object RootRepo {
    private const val TAG = "RootRepo"

    fun copyDatabases(context: Context): List<Pair<String, Long>> {
        val dbDir = File(context.filesDir, "dbs").apply { mkdirs() }
        val results = mutableListOf<Pair<String, Long>>()
        Shell.cmd("am force-stop com.whatsapp").exec()

        val ls = Shell.cmd("ls -lZ /data/data/com.whatsapp/databases/").exec()
        Log.e("RootRepo", "ls success=${ls.isSuccess} out=${ls.out} err=${ls.err}")

        val files = listOf(
            "msgstore.db","msgstore.db-wal","msgstore.db-shm",
            "wa.db","wa.db-wal","wa.db-shm"
        )
        for (name in files) {
            val dst = File(dbDir, name)
            try {
                // Use standard java Process to stream binary cat output directly into FileOutputStream
                val p = Runtime.getRuntime().exec(arrayOf("su", "--mount-master", "-c", "cat /data/data/com.whatsapp/databases/$name"))
                java.io.FileOutputStream(dst).use { out ->
                    p.inputStream.copyTo(out)
                }
                val code = p.waitFor()
                Log.e("RootRepo", "cat $name code=$code")
                
                if (dst.exists() && dst.length() > 0) results.add(name to dst.length())
                else dst.delete()
            } catch (e: Exception) {
                Log.e("RootRepo", "copy $name failed", e)
            }
        }
        return results
    }
}
