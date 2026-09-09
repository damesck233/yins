package moe.damesck.yins.hook.mediaprovider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import moe.damesck.yins.YLog
import moe.damesck.yins.data.PolicyContract

/**
 * Posts the "app is reading your photos" Live Update **from the MediaProvider process**.
 *
 * MediaProvider holds POST_NOTIFICATIONS (system-fixed) and is allowed to promote notifications,
 * and unlike the manager app it is always running, so ColorOS killing the manager or blocking
 * its start-up cannot delay the prompt. Icon and strings are read from the manager's APK
 * resources through PackageManager, with plain fallbacks.
 */
object LiveUpdatePoster {
    private const val CHANNEL_ID = "yins_media_access"
    // A photo read is a momentary event: keep the capsule up only briefly after the last read.
    // While an app browses continuously the debounced re-posts (see AccessNotifier) refresh it, so
    // it stays during active browsing and clears a few seconds after browsing stops.
    private const val TIMEOUT_MS = 10_000L
    private const val ACCENT = 0xFF6C4BF6.toInt() // yins brand purple
    private const val ADD_GRANTS_ACTIVITY = "moe.damesck.yins.ui.AddGrantsActivity"
    private const val EXTRA_TARGET_PACKAGE = "yins.targetPackage"

    fun post(context: Context, targetPackage: String): Boolean {
        return try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            ensureChannel(context, nm)
            val pm = context.packageManager
            val label = try {
                pm.getApplicationInfo(targetPackage, 0).loadLabel(pm).toString()
            } catch (t: Throwable) {
                targetPackage
            }
            val res = try {
                pm.getResourcesForApplication(PolicyContract.MANAGER_PACKAGE)
            } catch (t: Throwable) {
                null
            }
            // Resolve by *name*, never by compiled R ids: this code keeps running in MediaProvider
            // after the manager APK was updated, and the ids shift whenever resources are added.
            fun str(name: String, fallback: String, vararg args: Any): String = try {
                val id = res?.getIdentifier(name, "string", PolicyContract.MANAGER_PACKAGE) ?: 0
                if (id != 0) res!!.getString(id, *args) else fallback.format(*args)
            } catch (t: Throwable) {
                fallback.format(*args)
            }
            val iconId = try {
                res?.getIdentifier("ic_stat_yins", "drawable", PolicyContract.MANAGER_PACKAGE) ?: 0
            } catch (t: Throwable) {
                0
            }

            val tap = PendingIntent.getActivity(
                context,
                targetPackage.hashCode(),
                Intent()
                    .setClassName(PolicyContract.MANAGER_PACKAGE, ADD_GRANTS_ACTIVITY)
                    .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val icon = if (iconId != 0) {
                Icon.createWithResource(PolicyContract.MANAGER_PACKAGE, iconId)
            } else {
                Icon.createWithResource("android", android.R.drawable.ic_menu_gallery)
            }
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setColor(ACCENT)
                .setContentTitle(str("notify_reading_title", "%s 正在读取相册", label))
                .setContentText(str("notify_reading_text", "它目前只能看到你选中的照片，点击追加"))
                .setContentIntent(tap)
                .addAction(Notification.Action.Builder(icon, str("notify_action_add", "追加授权"), tap).build())
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setTimeoutAfter(TIMEOUT_MS)
                .setShortCriticalText(str("notify_capsule", "追加照片"))
            // Promotion to a Live Update (ColorOS 流体云) is gated by
            // Notification.hasPromotableCharacteristics(): the app must *request* it via the
            // EXTRA_REQUEST_PROMOTED_ONGOING extra (Builder.setRequestPromotedOngoing, @hide), NOT
            // by setting FLAG_PROMOTED_ONGOING directly — the system re-derives that flag and, when
            // only the flag was set, strips it and autogroups the notification. The other gates
            // (ongoing, has title, no custom style, not grouped, not colorized) are all satisfied.
            requestPromotedOngoing(builder)
            val notification = builder.build()
            nm.notify(notificationId(targetPackage), notification)
            val promoted = (notification.flags and FLAG_PROMOTED_ONGOING) != 0
            YLog.i("live update posted from MediaProvider for $targetPackage, canPost=${nm.canPostPromotedNotifications()}, promotedFlag=$promoted")
            true
        } catch (t: Throwable) {
            YLog.w("posting live update from MediaProvider failed", t)
            false
        }
    }

    private const val FLAG_PROMOTED_ONGOING = 0x40000 // Notification.FLAG_PROMOTED_ONGOING (Android 16)
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    /**
     * Requests promotion. Prefers the real (hidden) Builder API by reflection; falls back to
     * setting the extra directly, which is exactly what that method does.
     */
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

    private fun notificationId(packageName: String): Int = 0x5000 + (packageName.hashCode() and 0xFFFF)

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "yins 相册访问提示", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "部分授权的 App 读取相册时提示，可顺手追加可见的照片"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
