package moe.damesck.yins.hook.mediaprovider

import android.content.Context
import android.content.Intent
import android.os.Bundle
import moe.damesck.yins.YLog
import moe.damesck.yins.data.PolicyContract

/**
 * How MediaProvider's hook wakes the manager app.
 *
 * Primary: a synchronous call() on the manager's provider. The system starts the manager
 * process on the spot. Fallback: an explicit broadcast, which the system may defer for a long
 * time when the manager is cached, but which still eventually arrives.
 */
object ManagerLink {
    fun call(context: Context, method: String, extras: Bundle?): Boolean = try {
        val reply = context.contentResolver.call(PolicyContract.AUTHORITY, method, null, extras)
        reply?.getBoolean(PolicyContract.EXTRA_OK) == true
    } catch (t: Throwable) {
        YLog.w("manager call $method failed: $t")
        false
    }

    fun broadcast(context: Context, action: String, receiver: String, extras: Bundle?) {
        try {
            val intent = Intent(action)
                .setClassName(PolicyContract.MANAGER_PACKAGE, receiver)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            if (extras != null) intent.putExtras(extras)
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            YLog.w("manager broadcast $action failed", t)
        }
    }
}
