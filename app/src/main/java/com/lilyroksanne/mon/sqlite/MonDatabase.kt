package com.lilyroksanne.mon.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MemoryEntity::class], version = 1, exportSchema = false)
abstract class MonDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: MonDatabase? = null

        fun getDatabase(context: Context): MonDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MonDatabase::class.java,
                    "mon_episodic_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}