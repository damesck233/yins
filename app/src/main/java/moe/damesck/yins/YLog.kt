package moe.damesck.yins

import android.util.Log

/** Logs to logcat with tag "Yins"; also to the Xposed log when running inside a hooked process. */
object YLog {
    const val TAG = "Yins"

    @Volatile
    var xposedLogger: ((String) -> Unit)? = null

    fun d(msg: String) {
        Log.d(TAG, msg)
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        xposedLogger?.invoke("[I] $msg")
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
        xposedLogger?.invoke("[W] $msg${t?.let { ": $it" } ?: ""}")
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        xposedLogger?.invoke("[E] $msg${t?.let { ": ${Log.getStackTraceString(it)}" } ?: ""}")
    }
}
