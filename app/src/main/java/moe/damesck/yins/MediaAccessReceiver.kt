package moe.damesck.yins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.damesck.yins.data.PolicyContract
import moe.damesck.yins.notify.AccessNotifications

/** MediaProvider's hook reports that a PARTIAL app is reading photos; surface an "add photos" Live Update. */
class MediaAccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PolicyContract.ACTION_MEDIA_ACCESS) return
        val pkg = intent.getStringExtra(PolicyContract.EXTRA_PACKAGE) ?: return
        AccessNotifications.showReading(context, pkg)
    }
}
