package moe.damesck.yins.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PolicyDao {
    @Query("SELECT * FROM policies ORDER BY packageName")
    fun observeAll(): Flow<List<Policy>>

    @Query("SELECT * FROM policies ORDER BY packageName")
    fun getAllBlocking(): List<Policy>

    @Query("SELECT * FROM policies WHERE packageName = :packageName AND userId = :userId LIMIT 1")
    suspend fun get(packageName: String, userId: Int): Policy?

    @Upsert
    suspend fun upsert(policy: Policy)

    @Query("DELETE FROM policies WHERE packageName = :packageName AND userId = :userId")
    suspend fun delete(packageName: String, userId: Int)

    @Query("DELETE FROM policies WHERE packageName = :packageName")
    suspend fun deleteAllUsers(packageName: String)
}
