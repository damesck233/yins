package moe.damesck.yins.hook.mediaprovider

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import moe.damesck.yins.YLog
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicyContract
import moe.damesck.yins.data.PolicySnapshot
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * In-memory policy lookup for the MediaProvider process. Every FUSE / query decision hits
 * [modeForUid], so it must never do IPC: it resolves uid -> packages once per uid and caches.
 *
 * Sources, in order of authority:
 *  1. `yins.setPolicies` pushed by the manager (authoritative, also persisted here);
 *  2. the copy persisted in MediaProvider's own device-protected storage, loaded synchronously
 *     at startup so protection does not depend on the manager app being startable at boot;
 *  3. a pull from the manager's provider (best effort).
 */
object PolicyCache {
    private const val PER_USER_RANGE = 100_000
    private const val STORE_FILE = "yins_policies"

    @Volatile
    private var context: Context? = null

    @Volatile
    private var snapshot: PolicySnapshot = PolicySnapshot.EMPTY

    @Volatile
    var loaded: Boolean = false
        private set

    /** uid -> resolved mode; [NONE] marks "no policy" so the lookup is cached too. */
    private val uidModes = ConcurrentHashMap<Int, Any>()
    private val NONE = Any()

    /** uid -> the package whose policy matched (for notifications). */
    private val uidPackages = ConcurrentHashMap<Int, String>()

    val appContext: Context? get() = context

    fun packageForUid(uid: Int): String? = uidPackages[uid]

    fun start(ctx: Context) {
        context = ctx.applicationContext ?: ctx
        loadPersisted()
        observeManager()
        thread(name = "yins-policy-load") {
            var delay = 1_000L
            var attempts = 0
            // Refresh from the manager; keep trying for a while even if the persisted copy loaded,
            // in case policies changed while MediaProvider was not running.
            while (attempts < 8) {
                if (reload()) break
                requestPush()
                attempts++
                Thread.sleep(delay)
                delay = (delay * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun storeFile(): File? = try {
        File(context!!.createDeviceProtectedStorageContext().filesDir, STORE_FILE)
    } catch (t: Throwable) {
        null
    }

    private fun loadPersisted() {
        val file = storeFile() ?: return
        try {
            if (!file.exists()) return
            val entries = file.readLines().filter { it.isNotBlank() }.toTypedArray()
            snapshot = PolicySnapshot.decode(entries)
            uidModes.clear()
            uidPackages.clear()
            loaded = true
            YLog.i("policies loaded (persisted): ${snapshot.size}")
        } catch (t: Throwable) {
            YLog.w("loading persisted policies failed", t)
        }
    }

    private fun persist(fresh: PolicySnapshot) {
        val file = storeFile() ?: return
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(fresh.encode().joinToString("\n"))
            if (!tmp.renameTo(file)) file.writeText(fresh.encode().joinToString("\n"))
        } catch (t: Throwable) {
            YLog.w("persisting policies failed", t)
        }
    }

    /** Second path besides push: the manager notifies its provider URI on every change. */
    private fun observeManager() {
        val ctx = context ?: return
        try {
            val handler = Handler(Looper.getMainLooper())
            ctx.contentResolver.registerContentObserver(
                PolicyContract.POLICIES_URI,
                true,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        thread(name = "yins-policy-observer") {
                            if (!reload()) YLog.w("observer reload failed")
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            YLog.w("registerContentObserver failed", t)
        }
    }

    /** Pull from the manager's provider. May fail (manager not startable, SELinux); that's fine. */
    fun reload(): Boolean {
        val ctx = context ?: return false
        val fresh = PolicySnapshot.load(ctx) ?: return false
        set(fresh, "pull")
        return true
    }

    fun set(fresh: PolicySnapshot, source: String) {
        snapshot = fresh
        uidModes.clear()
        uidPackages.clear()
        loaded = true
        persist(fresh)
        YLog.i("policies loaded ($source): ${fresh.size}")
    }

    /** Ask the manager app to push its policy list: provider call first, broadcast as fallback. */
    fun requestPush() {
        val ctx = context ?: return
        if (!ManagerLink.call(ctx, PolicyContract.PROVIDER_METHOD_REQUEST_POLICIES, null)) {
            ManagerLink.broadcast(ctx, PolicyContract.ACTION_REQUEST_POLICIES, PolicyContract.PUSH_RECEIVER, null)
        }
    }

    val policyCount: Int get() = snapshot.size

    fun modeForUid(uid: Int): Mode? {
        if (!loaded) return null
        if (uid < Process.FIRST_APPLICATION_UID) return null
        val cached = uidModes[uid]
        if (cached != null) return cached as? Mode
        val resolved = resolve(uid)
        uidModes[uid] = resolved ?: NONE
        return resolved
    }

    fun modeForPackage(packageName: String, userId: Int): Mode? = snapshot.get(packageName, userId)

    private fun resolve(uid: Int): Mode? {
        val ctx = context ?: return null
        val packages = try {
            ctx.packageManager.getPackagesForUid(uid)
        } catch (t: Throwable) {
            null
        } ?: return null
        val userId = uid / PER_USER_RANGE
        for (pkg in packages) {
            snapshot.get(pkg, userId)?.let {
                uidPackages[uid] = pkg
                return it
            }
        }
        return null
    }
}
