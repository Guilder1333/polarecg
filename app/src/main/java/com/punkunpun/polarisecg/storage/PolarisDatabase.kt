package com.punkunpun.polarisecg.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's database (plan §8). Version 1: `beat`; version 2: `minute_summary` and `hrv_window` (the long-term view, P5.6);
 * version 3: `event` (P5.4). More tables (labels, sessions) come with their tasks, each one a schema [version] step with its SQL in `Migrations.kt`
 * (checked against `app/schemas/`); the summary rows and labels cannot be rebuilt once the raw files are gone.
 */
@Database(
    entities = [BeatEntity::class, MinuteEntity::class, HrvEntity::class, EventEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class PolarisDatabase : RoomDatabase() {
    abstract fun beatDao(): BeatDao
    abstract fun summaryDao(): SummaryDao
    abstract fun eventDao(): EventDao

    companion object {
        const val FILE_NAME = "polaris.db"

        fun open(context: Context): PolarisDatabase =
            Room.databaseBuilder(context, PolarisDatabase::class.java, FILE_NAME).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
