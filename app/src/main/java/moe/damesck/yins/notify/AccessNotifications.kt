package moe.damesck.yins.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import moe.damesck.yins.R
import moe.damesck.yins.YLog
import moe.damesck.yins.ui.AddGrantsActivity
import moe.damesck.yins.ui.AppCatalog

/**
 * "App X is reading your photos" as an Android 16 Live Update (promoted ongoing notification).
 * On ColorOS 16 this lands in the status capsule; where promotion is not honoured it degrades to
 * a normal heads-up notification with the same action.
 */
object AccessNotifications {
    private const val CHANNEL_ID = "media_access"
    private const val TIMEOUT_MS = 10_000L
    private const val ACCENT = 0xFF6C4BF6.toInt()

    fun showReading(context: Context, packageName: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            YLog.w("POST_NOTIFICATIONS not granted; cannot show media access notification")
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context, nm)

        val label = AppCatalog.label(context, packageName)
        val tap = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            Intent(context, AddGrantsActivity::class.java)
                .putExtra(AddGrantsActivity.EXTRA_TARGET_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_stat_yins)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yins)
            .setColor(ACCENT)
            .setContentTitle(context.getString(R.string.notify_reading_title, label))
            .setContentText(context.getString(R.string.notify_reading_text))
            .setContentIntent(tap)
            .addAction(Notification.Action.Builder(icon, context.getString(R.string.notify_action_add), tap).build())
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setShortCriticalText(context.getString(R.string.notify_capsule))
        // Request promotion to a Live Update (ColorOS 流体云). This must go through the
        // EXTRA_REQUEST_PROMOTED_ONGOING extra (Builder.setRequestPromotedOngoing, @hide), NOT by
        // setting FLAG_PROMOTED_ONGOING directly, which the system strips. The manager app holds
        // POST_PROMOTED_NOTIFICATIONS, so unlike the MediaProvider process it is actually allowed
        // to promote — which is why the "reading your photos" notification is posted from here.
        requestPromotedOngoing(builder)

        val notification = builder.build()
        nm.notify(notificationId(packageName), notification)
        val promoted = (notification.flags and FLAG_PROMOTED_ONGOING) != 0
        YLog.i("media access notification for $packageName, canPost=${nm.canPostPromotedNotifications()}, promotedFlag=$promoted")
    }

    private const val FLAG_PROMOTED_ONGOING = 0x40000 // Notification.FLAG_PROMOTED_ONGOING (Android 16)
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    private fun requestPromotedOngoing(builder: Notification.Builder) {
        val viaMethod = runCatching {
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
        }.isSuccess
        if (!viaMethod) {
            builder.addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
    }

    fun cancel(context: Context, packageName: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(packageName))
    }

    private fun notificationId(packageName: String): Int = 0x5000 + (packageName.hashCode() and 0xFFFF)

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notify_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notify_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
