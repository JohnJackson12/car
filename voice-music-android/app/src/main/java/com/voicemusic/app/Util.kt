package com.voicemusic.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Tiny threading helpers: heavy work on `io`, UI/state changes on `post` (main thread). */
object Bg {
    val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "vm-io").apply { isDaemon = true } }
    fun io(r: () -> Unit) { pool.execute { try { r() } catch (e: Throwable) { CrashLog.note("background task failed", e) } } }
    fun post(r: () -> Unit) { main.post { try { r() } catch (e: Throwable) { CrashLog.note("main task failed", e) } } }
    fun postDelayed(ms: Long, r: () -> Unit) { main.postDelayed({ try { r() } catch (e: Throwable) { CrashLog.note("delayed task failed", e) } }, ms) }
    fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()
}

fun fmtTime(seconds: Double?): String {
    val s = Math.max(0, (seconds ?: 0.0).toInt())
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

fun fmtTrimShort(seconds: Double): String {
    val s = Math.max(0, seconds.toInt())
    return if (s < 60) "${s}s" else fmtTime(seconds)
}
