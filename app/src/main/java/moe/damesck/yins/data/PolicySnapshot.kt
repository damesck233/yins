package moe.damesck.yins.data

import android.content.Context

/**
 * Immutable view of all policies, keyed by (packageName, userId).
 * Used by the hooked processes; built either by querying [PolicyProvider] or from the
 * encoded list the manager pushes through MediaProvider.call().
 */
class PolicySnapshot private constructor(private val map: Map<Key, Entry>) {
    data class Key(val packageName: String, val userId: Int)
    data class Entry(val mode: Mode, val hideDirectories: Boolean)

    val size: Int get() = map.size

    fun get(packageName: String, userId: Int): Mode? = map[Key(packageName, userId)]?.mode

    fun hideDirectories(packageName: String, userId: Int): Boolean =
        map[Key(packageName, userId)]?.hideDirectories == true

    fun encode(): Array<String> = map.entries.map {
        "${it.key.packageName}|${it.key.userId}|${it.value.mode.name}|${if (it.value.hideDirectories) 1 else 0}"
    }.toTypedArray()

    companion object {
        val EMPTY = PolicySnapshot(emptyMap())

        fun of(policies: Collection<Policy>): PolicySnapshot =
            PolicySnapshot(policies.associate { Key(it.packageName, it.userId) to Entry(it.mode, it.hideDirectories) })

        fun decode(entries: Array<String>?): PolicySnapshot {
            if (entries == null) return EMPTY
            val result = HashMap<Key, Entry>()
            for (e in entries) {
                val parts = e.split('|')
                if (parts.size < 3) continue
                val userId = parts[1].toIntOrNull() ?: continue
                val mode = Mode.fromName(parts[2]) ?: continue
                val hideDirs = parts.getOrNull(3) == "1"
                result[Key(parts[0], userId)] = Entry(mode, hideDirs)
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
                val hideIdx = it.getColumnIndex(PolicyContract.COL_HIDE_DIRS)
                val result = HashMap<Key, Entry>()
                while (it.moveToNext()) {
                    val mode = Mode.fromName(it.getString(modeIdx)) ?: continue
                    val hideDirs = hideIdx >= 0 && it.getInt(hideIdx) != 0
                    result[Key(it.getString(pkgIdx), it.getInt(userIdx))] = Entry(mode, hideDirs)
                }
                return PolicySnapshot(result)
            }
        }
    }
}
