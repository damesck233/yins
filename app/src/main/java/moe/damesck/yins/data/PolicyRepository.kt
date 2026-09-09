package moe.damesck.yins.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class PolicyRepository(private val context: Context) {
    private val dao get() = PolicyDatabase.get(context).policyDao()

    fun observeAll(): Flow<List<Policy>> = dao.observeAll()

    suspend fun get(packageName: String, userId: Int = 0): Policy? = dao.get(packageName, userId)

    /** Persist a policy and push the full list into MediaProvider. */
    suspend fun set(policy: Policy) {
        dao.upsert(policy)
        changed()
    }

    suspend fun remove(packageName: String, userId: Int = 0) {
        dao.delete(packageName, userId)
        withContext(Dispatchers.IO) { MediaProviderClient.clearGrants(context, packageName, userId) }
        changed()
    }

    suspend fun removeAllUsers(packageName: String) {
        dao.deleteAllUsers(packageName)
        changed()
    }

    suspend fun push(): Boolean = withContext(Dispatchers.IO) { MediaProviderClient.pushAll(context) }

    /**
     * Two independent paths to MediaProvider: an explicit push, and a change notification on our
     * provider URI that MediaProvider's hook observes and answers with a pull.
     */
    private suspend fun changed() {
        try {
            context.contentResolver.notifyChange(PolicyContract.POLICIES_URI, null)
        } catch (t: Throwable) {
            // notification is best-effort
        }
        push()
    }
}
