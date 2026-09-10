package moe.damesck.yins.hook.mediaprovider

import android.content.Context
import android.os.SystemClock
import moe.damesck.yins.YLog
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * A rolling record, kept in the MediaProvider process, of managed apps trying to read your media:
 * who, when, under which mode, and whether it was blocked. The manager reads it back over the
 * control channel (METHOD_GET_LOG). Debounced per uid so a burst of permission checks from one
 * query becomes a single entry. Persisted to device-protected storage so it survives a
 * MediaProvider restart.
 */
object AccessLog {
    private const val CAP = 300
    private const val DEBOUNCE_MS = 8_000L
    private const val STORE_FILE = "yins_access_log"
    private const val SEP = ""

    /** wallClock = epoch millis; blocked = the read was fully masked (BLANK), else limited (PARTIAL). */
    data class Entry(val wallClock: Long, val pkg: String, val mode: String, val blocked: Boolean) {
        fun encode(): String = listOf(wallClock.toString(), pkg, mode, if (blocked) "1" else "0").joinToString(SEP)

        companion object {
            fun decode(s: String): Entry? {
                val p = s.split(SEP)
                if (p.size != 4) return null
                val t = p[0].toLongOrNull() ?: return null
                return Entry(t, p[1], p[2], p[3] == "1")
            }
        }
    }

    private val buffer = ArrayDeque<Entry>()
    private val lastPerUid = ConcurrentHashMap<Int, Long>()

    @Volatile private var context: Context? = null
    @Volatile private var loaded = false

    fun record(ctx: Context?, uid: Int, pkg: String?, mode: String, blocked: Boolean) {
        if (ctx == null || pkg == null) return
        context = ctx.applicationContext ?: ctx
        val now = SystemClock.elapsedRealtime()
        val last = lastPerUid[uid] ?: 0L
        if (now - last < DEBOUNCE_MS) return
        lastPerUid[uid] = now
        ensureLoaded()
        val entry = Entry(System.currentTimeMillis(), pkg, mode, blocked)
        synchronized(buffer) {
            buffer.addFirst(entry)
            while (buffer.size > CAP) buffer.removeLast()
            persistLocked()
        }
    }

    /** Newest first, encoded for transport in a Bundle. */
    fun encodeAll(): Array<String> {
        ensureLoaded()
        synchronized(buffer) { return buffer.map { it.encode() }.toTypedArray() }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            persistLocked()
        }
    }

    private fun storeFile(): File? = try {
        File(context!!.createDeviceProtectedStorageContext().filesDir, STORE_FILE)
    } catch (t: Throwable) {
        null
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(buffer) {
            if (loaded) return
            loaded = true
            val file = storeFile() ?: return
            try {
                if (!file.exists()) return
                file.readLines().forEach { line ->
                    if (line.isNotBlank()) Entry.decode(line)?.let { buffer.addLast(it) }
                }
            } catch (t: Throwable) {
                YLog.w("loading access log failed", t)
            }
        }
    }

    private fun persistLocked() {
        val file = storeFile() ?: return
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(buffer.joinToString("\n") { it.encode() })
            tmp.renameTo(file)
        } catch (t: Throwable) {
            YLog.w("persisting access log failed", t)
        }
    }
}
