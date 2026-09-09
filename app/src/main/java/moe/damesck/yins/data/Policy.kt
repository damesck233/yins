package moe.damesck.yins.data

import androidx.room.Entity

enum class Mode {
    /** Real grant, nothing hidden. */
    FULL,

    /** Real grant, but MediaProvider only shows files the app itself created. */
    BLANK,

    /** Real grant, MediaProvider shows own files plus user-selected media (media_grants). */
    PARTIAL,

    /** Not granted; the permission dialog is answered with DENIED without asking again. */
    DENY;

    companion object {
        fun fromName(name: String?): Mode? = entries.firstOrNull { it.name == name }
    }
}

@Entity(tableName = "policies", primaryKeys = ["packageName", "userId"])
data class Policy(
    val packageName: String,
    val userId: Int = 0,
    val mode: Mode,
    val updatedAt: Long = System.currentTimeMillis(),
)
