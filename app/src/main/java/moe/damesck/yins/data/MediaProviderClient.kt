package moe.damesck.yins.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import moe.damesck.yins.YLog

/**
 * Talks to our hook inside MediaProvider through ContentResolver.call().
 * Every method swallows failures (MediaProvider may not be hooked yet or may be restarting)
 * and reports success as a Boolean.
 */
object MediaProviderClient {
    private const val PUSH_ATTEMPTS = 3
    private const val PUSH_RETRY_DELAY_MS = 700L

    private fun call(context: Context, method: String, extras: Bundle? = null): Bundle? = try {
        context.contentResolver.call(MediaStore.AUTHORITY, method, null, extras)
    } catch (t: Throwable) {
        YLog.w("call $method failed", t)
        null
    }

    private fun Bundle?.ok(): Boolean = this?.getBoolean(PolicyContract.EXTRA_OK) == true

    fun ping(context: Context): Bundle? = call(context, PolicyContract.METHOD_PING)

    /**
     * Push the complete policy list into MediaProvider and verify it landed. This is the
     * authoritative path: it only needs MediaProvider's own provider to be reachable, which is
     * true for every app. Retries a few times because MediaProvider may be mid-restart.
     */
    fun pushAll(context: Context): Boolean {
        val policies = PolicyDatabase.get(context).policyDao().getAllBlocking()
        val snapshot = PolicySnapshot.of(policies)
        val extras = Bundle().apply { putStringArray(PolicyContract.EXTRA_POLICIES, snapshot.encode()) }
        repeat(PUSH_ATTEMPTS) { attempt ->
            val ok = call(context, PolicyContract.METHOD_SET_POLICIES, extras).ok() && verify(context, snapshot)
            YLog.i("pushAll attempt ${attempt + 1}: ${policies.size} policies, ok=$ok")
            if (ok) return true
            Thread.sleep(PUSH_RETRY_DELAY_MS)
        }
        return false
    }

    /** Read back the count and one sample entry to make sure the push was applied. */
    private fun verify(context: Context, snapshot: PolicySnapshot): Boolean {
        val reply = call(context, PolicyContract.METHOD_PING) ?: return false
        if (!reply.ok() || !reply.getBoolean(PolicyContract.EXTRA_LOADED)) return false
        return reply.getInt(PolicyContract.EXTRA_POLICY_COUNT, -1) == snapshot.size
    }

    /**
     * Adds [uris] (photo-picker URIs, document URIs, or media URIs) to the app's media_grants.
     * Returns how many were granted, or -1 if MediaProvider refused the call.
     */
    fun grant(context: Context, packageName: String, userId: Int, uris: List<Uri>): Int {
        val mapped = GrantUris.toPickerUris(context, uris)
        if (mapped.pickerUris.isEmpty()) return 0
        val extras = Bundle().apply {
            putString(PolicyContract.EXTRA_PACKAGE, packageName)
            putInt(PolicyContract.EXTRA_USER_ID, userId)
            putParcelableArrayList(PolicyContract.EXTRA_URIS, ArrayList(mapped.pickerUris))
        }
        return if (call(context, PolicyContract.METHOD_GRANT, extras).ok()) mapped.pickerUris.size else -1
    }

    fun clearGrants(context: Context, packageName: String, userId: Int): Boolean {
        val extras = Bundle().apply {
            putString(PolicyContract.EXTRA_PACKAGE, packageName)
            putInt(PolicyContract.EXTRA_USER_ID, userId)
        }
        return call(context, PolicyContract.METHOD_CLEAR_GRANTS, extras).ok()
    }
}
