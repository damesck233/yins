package moe.damesck.yins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.damesck.yins.data.MediaProviderClient
import moe.damesck.yins.data.PolicyContract
import kotlin.concurrent.thread

/** MediaProvider's hook sends this when it starts without policies; answer with a push. */
class PolicyPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PolicyContract.ACTION_REQUEST_POLICIES) return
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "yins-push") {
            try {
                MediaProviderClient.pushAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}
