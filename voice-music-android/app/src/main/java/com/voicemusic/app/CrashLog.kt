package com.voicemusic.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes crashes/notable errors to crash_log.txt so problems on a car unit can be diagnosed afterwards. */
object CrashLog {
    private var files: List<File> = emptyList()

    fun install(ctx: Context) {
        val list = ArrayList<File>()
        list.add(File(ctx.filesDir, "crash_log.txt"))
        try { ctx.getExternalFilesDir(null)?.let { list.add(File(it, "crash_log.txt")) } } catch (e: Exception) { }
        files = list
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write("UNCAUGHT in thread ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }

    fun note(msg: String, e: Throwable? = null) {
        Log.w("VoiceMusic", msg, e)
        write(msg, e)
    }

    @Synchronized
    private fun write(header: String, e: Throwable?) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder("\n==== $header - $stamp\n")
        if (e != null) { val sw = StringWriter(); e.printStackTrace(PrintWriter(sw)); sb.append(sw.toString()) }
        for (f in files) {
            try {
                if (f.exists() && f.length() > 200_000) f.delete()
                f.appendText(sb.toString())
            } catch (ex: Exception) { }
        }
    }

    fun read(): String {
        val f = files.firstOrNull { it.exists() } ?: return "(no crash log yet - good!)"
        return try { f.readText().takeLast(20000) } catch (e: Exception) { "(couldn't read log: $e)" }
    }

    fun clear() { for (f in files) try { f.delete() } catch (e: Exception) { } }
    fun path(): String = files.lastOrNull()?.absolutePath ?: ""
}
