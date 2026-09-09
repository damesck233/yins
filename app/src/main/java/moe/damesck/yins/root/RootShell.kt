package moe.damesck.yins.root

import com.topjohnwu.superuser.Shell
import moe.damesck.yins.BuildConfig
import moe.damesck.yins.YLog

object RootShell {
    fun init() {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                // Only ever run `su`: without this libsu silently falls back to a plain `sh` when
                // su is unavailable (KernelSU/SukiSU hide it from apps that are not allowed) and
                // caches that non-root shell for the rest of the process.
                .setCommands("su")
                .setTimeout(20),
        )
    }

    /** Blocking; do not call on the main thread. Rebuilds the shell if the cached one is not root. */
    @Synchronized
    fun isRoot(): Boolean {
        Shell.getCachedShell()?.let { cached ->
            if (cached.isRoot && cached.isAlive) return true
            runCatching { cached.close() }
        }
        return try {
            Shell.getShell().isRoot
        } catch (t: Throwable) {
            YLog.w("su unavailable: $t")
            false
        }
    }

    /** Blocking; runs every command in order regardless of individual failures. */
    fun run(commands: List<String>): Shell.Result = Shell.cmd(*commands.toTypedArray()).exec()
}
