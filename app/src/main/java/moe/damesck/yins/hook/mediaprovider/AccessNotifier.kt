package moe.damesck.yins.hook.mediaprovider

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import moe.damesck.yins.YLog
import moe.damesck.yins.data.PolicyContract
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Surfaces "a PARTIAL-mode app is reading the photo library" so the user can add more photos.
 * Debounced per uid and dispatched off the MediaProvider hot path.
 *
 * The Live Update is posted from this (MediaProvider) process; the manager app is only asked
 * as a fallback if that fails.
 */
object AccessNotifier {
    // Re-post at most this often. Shorter than the notification's timeout so an app that keeps
    // reading refreshes the capsule (keeping it up during active browsing), while a single read
    // lets it lapse. setOnlyAlertOnce makes the refresh silent.
    private const val DEBOUNCE_MS = 6_000L

    private val lastReport = ConcurrentHashMap<Int, Long>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "yins-access-notify") }

    fun report(context: Context?, uid: Int, packageName: String?) {
        if (context == null || packageName == null) return
        val now = SystemClock.elapsedRealtime()
        val last = lastReport[uid] ?: 0L
        if (now - last < DEBOUNCE_MS) return
        lastReport[uid] = now
        executor.execute {
            // Posted from this (MediaProvider) process, which is always alive — ColorOS often blocks
            // MediaProvider from waking the killed manager. MediaProvider itself lacks
            // POST_PROMOTED_NOTIFICATIONS, so the promotion to a Live Update (流体云) is forced by our
            // system_server hook (LiveUpdatePromotionHook) for this exact channel.
            if (LiveUpdatePoster.post(context, packageName)) return@execute
            // Last resort if even that failed: ask the manager (only works if it happens to be alive).
            val extras = Bundle().apply {
                putString(PolicyContract.EXTRA_PACKAGE, packageName)
                putInt(PolicyContract.EXTRA_UID, uid)
            }
            val direct = ManagerLink.call(context, PolicyContract.PROVIDER_METHOD_MEDIA_ACCESS, extras)
            if (!direct) {
                ManagerLink.broadcast(context, PolicyContract.ACTION_MEDIA_ACCESS, PolicyContract.MEDIA_ACCESS_RECEIVER, extras)
            }
            YLog.i("media access reported to manager (fallback): $packageName (direct=$direct)")
        }
    }
}
