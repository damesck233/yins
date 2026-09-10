package moe.damesck.yins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread
import moe.damesck.yins.data.GrantTimers
import moe.damesck.yins.data.MediaProviderClient
import moe.damesck.yins.data.PolicyContract

/** Fired by AlarmManager when a temporary partial grant's window elapses: revoke the picked photos. */
class GrantExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(PolicyContract.EXTRA_PACKAGE) ?: return
        val user = intent.getIntExtra(PolicyContract.EXTRA_USER_ID, 0)
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "yins-grant-expiry") {
            try {
                MediaProviderClient.clearGrants(app, pkg, user)
                GrantTimers.cancel(app, pkg, user)
                YLog.i("temporary grant expired, revoked for $pkg")
            } finally {
                pending.finish()
            }
        }
    }
}
