package com.proedit.nle.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class ProEditDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var instance: ProEditDatabase? = null

        fun getInstance(context: Context): ProEditDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProEditDatabase::class.java,
                    "proedit_nle.db"
                ).build().also { instance = it }
            }
        }
    }
}
