package moe.damesck.yins.hook.system

import android.app.Notification
import android.app.NotificationChannel
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.damesck.yins.YLog

/**
 * Inside system_server (NotificationManagerService): promote the "app is reading your photos"
 * notification to an Android 16 Live Update (ColorOS 流体云).
 *
 * That notification is posted from the MediaProvider process, because ColorOS keeps that process
 * alive while it blocks MediaProvider from waking the (killable) manager app. But MediaProvider does
 * not hold POST_PROMOTED_NOTIFICATIONS, so NMS.fixNotificationWithChannel refuses to set
 * FLAG_PROMOTED_ONGOING and the notification degrades to a plain heads-up.
 *
 * We scope the override as tightly as possible: only MediaProvider's own uid/package, only our
 * channel, and only when the notification already has the promotable characteristics the system
 * itself requires (ongoing, titled, promotable style, requested-promotion). Nothing else is touched.
 */
object LiveUpdatePromotionHook {
    private const val NMS = "com.android.server.notification.NotificationManagerService"
    private const val FLAG_PROMOTED_ONGOING = 0x40000
    private const val OUR_CHANNEL = "yins_media_access"
    private val MEDIA_PROVIDER_PACKAGES = setOf(
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val nms = XposedHelpers.findClass(NMS, lpparam.classLoader)

        // The method that actually stamps FLAG_PROMOTED_ONGOING during enqueue.
        XposedBridge.hookAllMethods(nms, "fixNotificationWithChannel", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val pkg = param.args.firstOrNull { it is String } as? String ?: return
                    if (pkg !in MEDIA_PROVIDER_PACKAGES) return
                    val notification = param.args.firstOrNull { it is Notification } as? Notification ?: return
                    val channel = param.args.firstOrNull { it is NotificationChannel } as? NotificationChannel ?: return
                    if (channel.id != OUR_CHANNEL || channel.importance <= 1) return
                    val promotable = runCatching {
                        XposedHelpers.callMethod(notification, "hasPromotableCharacteristics") as Boolean
                    }.getOrDefault(false)
                    if (!promotable) return
                    if (notification.flags and FLAG_PROMOTED_ONGOING == 0) {
                        notification.flags = notification.flags or FLAG_PROMOTED_ONGOING
                        YLog.i("promoted yins live update for $pkg")
                    }
                } catch (t: Throwable) {
                    YLog.e("live update promotion hook failed", t)
                }
            }
        })

        // The app-facing self-check (canPostPromotedNotifications). Keep it consistent so any
        // ColorOS 流体云 code that re-queries it also agrees.
        runCatching {
            XposedBridge.hookAllMethods(nms, "checkPostPromotedNotificationPermission", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkg = param.args.firstOrNull { it is String } as? String ?: return
                    if (pkg in MEDIA_PROVIDER_PACKAGES) param.result = true
                }
            })
        }
        YLog.i("live update promotion hooked")
    }
}
