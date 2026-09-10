package moe.damesck.yins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.damesck.yins.data.MediaProviderClient
import kotlin.concurrent.thread

/**
 * After boot, push the policy list into MediaProvider (with retries: MediaProvider may still be
 * starting). The MediaProvider hook also asks for a push itself via [PolicyPushReceiver].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "yins-boot-push") {
            try {
                kotlinx.coroutines.runBlocking { moe.damesck.yins.data.GrantTimers.reconcile(app) }
                repeat(RETRIES) { attempt ->
                    if (MediaProviderClient.pushAll(app)) {
                        YLog.i("boot push ok after ${attempt + 1} attempt(s)")
                        return@thread
                    }
                    Thread.sleep(RETRY_DELAY_MS)
                }
                YLog.w("boot push failed after $RETRIES attempts")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val RETRIES = 6
        const val RETRY_DELAY_MS = 5_000L
    }
}
