package moe.damesck.yins.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class ModeConverters {
    @TypeConverter
    fun modeToString(mode: Mode): String = mode.name

    @TypeConverter
    fun stringToMode(value: String): Mode = Mode.valueOf(value)
}

@Database(entities = [Policy::class], version = 2, exportSchema = false)
@TypeConverters(ModeConverters::class)
abstract class PolicyDatabase : RoomDatabase() {
    abstract fun policyDao(): PolicyDao

    companion object {
        private const val DB_NAME = "policies.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE policies ADD COLUMN hideDirectories INTEGER NOT NULL DEFAULT 0")
            }
        }

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
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
