package moe.damesck.yins.data

import android.content.Context

/**
 * Immutable view of all policies, keyed by (packageName, userId).
 * Used by the hooked processes; built either by querying [PolicyProvider] or from the
 * encoded list the manager pushes through MediaProvider.call().
 */
class PolicySnapshot private constructor(private val map: Map<Key, Mode>) {
    data class Key(val packageName: String, val userId: Int)

    val size: Int get() = map.size

    fun get(packageName: String, userId: Int): Mode? = map[Key(packageName, userId)]

    fun encode(): Array<String> = map.entries.map { "${it.key.packageName}|${it.key.userId}|${it.value.name}" }.toTypedArray()

    companion object {
        val EMPTY = PolicySnapshot(emptyMap())

        fun of(policies: Collection<Policy>): PolicySnapshot =
            PolicySnapshot(policies.associate { Key(it.packageName, it.userId) to it.mode })

        fun decode(entries: Array<String>?): PolicySnapshot {
            if (entries == null) return EMPTY
            val result = HashMap<Key, Mode>()
            for (e in entries) {
                val parts = e.split('|')
                if (parts.size != 3) continue
                val userId = parts[1].toIntOrNull() ?: continue
                val mode = Mode.fromName(parts[2]) ?: continue
                result[Key(parts[0], userId)] = mode
            }
            return PolicySnapshot(result)
        }

        /** Query the manager's provider. Returns null if the provider is unreachable. */
        fun load(context: Context): PolicySnapshot? {
            val cursor = try {
                context.contentResolver.query(PolicyContract.POLICIES_URI, null, null, null, null)
            } catch (t: Throwable) {
                return null
            } ?: return null
            cursor.use {
                val pkgIdx = it.getColumnIndexOrThrow(PolicyContract.COL_PACKAGE)
                val userIdx = it.getColumnIndexOrThrow(PolicyContract.COL_USER_ID)
                val modeIdx = it.getColumnIndexOrThrow(PolicyContract.COL_MODE)
                val result = HashMap<Key, Mode>()
                while (it.moveToNext()) {
                    val mode = Mode.fromName(it.getString(modeIdx)) ?: continue
                    result[Key(it.getString(pkgIdx), it.getInt(userIdx))] = mode
                }
                return PolicySnapshot(result)
            }
        }
    }
}
