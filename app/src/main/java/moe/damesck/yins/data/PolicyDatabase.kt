package moe.damesck.yins.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class ModeConverters {
    @TypeConverter
    fun modeToString(mode: Mode): String = mode.name

    @TypeConverter
    fun stringToMode(value: String): Mode = Mode.valueOf(value)
}

@Database(entities = [Policy::class], version = 1, exportSchema = false)
@TypeConverters(ModeConverters::class)
abstract class PolicyDatabase : RoomDatabase() {
    abstract fun policyDao(): PolicyDao

    companion object {
        private const val DB_NAME = "policies.db"

        @Volatile
        private var instance: PolicyDatabase? = null

        /**
         * The database lives in device-protected storage so that [PolicyProvider] can serve
         * MediaProvider before the user unlocks the device after a reboot.
         */
        fun get(context: Context): PolicyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext.createDeviceProtectedStorageContext(),
                PolicyDatabase::class.java,
                DB_NAME,
            ).build().also { instance = it }
        }
    }
}
