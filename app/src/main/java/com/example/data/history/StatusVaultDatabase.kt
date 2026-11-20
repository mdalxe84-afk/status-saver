package com.example.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryStatusEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StatusVaultDatabase : RoomDatabase() {
    abstract fun historyStatusDao(): HistoryStatusDao

    companion object {
        @Volatile
        private var INSTANCE: StatusVaultDatabase? = null

        fun getInstance(context: Context): StatusVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StatusVaultDatabase::class.java,
                    "status_vault_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
