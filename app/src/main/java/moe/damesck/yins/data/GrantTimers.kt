package moe.damesck.yins.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.damesck.yins.GrantExpiryReceiver
import moe.damesck.yins.YLog

/**
 * "Temporary" partial grants: after a configured duration the app's picked photos are revoked
 * automatically, so a one-off share doesn't leave it able to see those photos forever.
 *
 * The per-app duration and the current expiry are stored in SharedPreferences; the actual revoke is
 * driven by an inexact-but-doze-friendly AlarmManager alarm, with a reconcile pass on manager open
 * and boot as a backstop in case ColorOS drops the alarm.
 */
object GrantTimers {
    private const val PREFS = "grant_timers"
    private fun keyMin(pkg: String, user: Int) = "min:$pkg:$user"
    private fun keyExp(pkg: String, user: Int) = "exp:$pkg:$user"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Configured auto-revoke window in minutes; 0 means "keep until cleared". */
    fun durationMinutes(context: Context, pkg: String, user: Int): Int =
        prefs(context).getInt(keyMin(pkg, user), 0)

    /** Epoch millis when the current grants expire; 0 if no timer is running. */
    fun expiryAt(context: Context, pkg: String, user: Int): Long =
        prefs(context).getLong(keyExp(pkg, user), 0L)

    /** Set the window and (re)start or cancel the timer accordingly. */
    fun setDuration(context: Context, pkg: String, user: Int, minutes: Int) {
        prefs(context).edit().putInt(keyMin(pkg, user), minutes).apply()
        if (minutes > 0) startTimer(context, pkg, user, minutes) else cancel(context, pkg, user)
    }

    /** Call after new photos are granted: (re)start the window if this app uses one. */
    fun onGranted(context: Context, pkg: String, user: Int) {
        val minutes = durationMinutes(context, pkg, user)
        if (minutes > 0) startTimer(context, pkg, user, minutes)
    }

    private fun startTimer(context: Context, pkg: String, user: Int, minutes: Int) {
        val expiry = System.currentTimeMillis() + minutes * 60_000L
        prefs(context).edit().putLong(keyExp(pkg, user), expiry).apply()
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expiry, pendingIntent(context, pkg, user))
        } catch (t: Throwable) {
            YLog.w("scheduling grant expiry failed", t)
        }
    }

    fun cancel(context: Context, pkg: String, user: Int) {
        prefs(context).edit().remove(keyExp(pkg, user)).apply()
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, pkg, user))
    }

    /** Revoke any grants whose timer has already elapsed. Safe to call often. */
    suspend fun reconcile(context: Context) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val p = prefs(context)
        val expired = p.all.entries
            .filter { it.key.startsWith("exp:") && (it.value as? Long ?: 0L) in 1..now }
            .map { it.key.removePrefix("exp:") }
        for (id in expired) {
            val idx = id.lastIndexOf(':')
            if (idx <= 0) continue
            val pkg = id.substring(0, idx)
            val user = id.substring(idx + 1).toIntOrNull() ?: continue
            runCatching { MediaProviderClient.clearGrants(context, pkg, user) }
            p.edit().remove("exp:$id").apply()
            YLog.i("reconcile: revoked expired grants for $pkg")
        }
    }

    private fun pendingIntent(context: Context, pkg: String, user: Int): PendingIntent {
        val intent = Intent(context, GrantExpiryReceiver::class.java)
            .setAction("moe.damesck.yins.action.GRANT_EXPIRED")
            .putExtra(PolicyContract.EXTRA_PACKAGE, pkg)
            .putExtra(PolicyContract.EXTRA_USER_ID, user)
        return PendingIntent.getBroadcast(
            context,
            "$pkg:$user".hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
