package moe.damesck.yins.hook.system

import android.content.Context
import android.database.ContentObserver
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import moe.damesck.yins.YLog
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicyContract
import moe.damesck.yins.data.PolicySnapshot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Policies as seen from system_server: pulled from the manager's provider on a private thread and
 * refreshed through a ContentObserver. Lookups are in-memory only, so they are safe to call from
 * binder threads.
 */
object SystemPolicyCache {
    private const val RETRY_MS = 5_000L
    private const val MAX_RETRIES = 120

    @Volatile private var snapshot: PolicySnapshot = PolicySnapshot.EMPTY
    private val started = AtomicBoolean(false)
    private lateinit var handler: Handler

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val thread = HandlerThread("yins-policy").apply { start() }
        handler = Handler(thread.looper)
        handler.post { observe(context) }
        handler.post { load(context, attempt = 0) }
    }

    fun get(packageName: String, userId: Int): Mode? = snapshot.get(packageName, userId)

    private fun observe(context: Context) {
        try {
            context.contentResolver.registerContentObserver(
                PolicyContract.POLICIES_URI,
                false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = load(context, attempt = 0)
                },
            )
        } catch (t: Throwable) {
            YLog.w("system policy observer failed", t)
        }
    }

    private fun load(context: Context, attempt: Int) {
        val token = Binder.clearCallingIdentity()
        val snap = try {
            PolicySnapshot.load(context)
        } catch (t: Throwable) {
            null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        if (snap == null) {
            if (attempt < MAX_RETRIES) handler.postDelayed({ load(context, attempt + 1) }, RETRY_MS)
            else YLog.w("system policy load gave up")
            return
        }
        snapshot = snap
        YLog.i("system policies loaded: ${snap.size}")
    }
}
