package com.example.tice.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        StudentEntity::class,
        ScoreEntity::class,
        ProjectRuleEntity::class,
        SessionEntity::class,
        MetaEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TiceDatabase : RoomDatabase() {
    abstract fun dao(): TiceDao

    companion object {
        fun build(context: Context): TiceDatabase =
            Room.databaseBuilder(context.applicationContext, TiceDatabase::class.java, "tice.db")
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .fallbackToDestructiveMigration()
                .build()
    }
}
