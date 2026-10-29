package com.punkunpun.polarisecg.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's database (plan §8). Only derived data lives here; the raw recordings are files, so this database
 * can be rebuilt from them. More tables (events, HRV windows, labels) are added with their tasks, each one a
 * schema [version] step; labels will need a real migration because they cannot be rebuilt.
 */
@Database(entities = [BeatEntity::class], version = 1, exportSchema = false)
abstract class PolarisDatabase : RoomDatabase() {
    abstract fun beatDao(): BeatDao

    companion object {
        fun open(context: Context): PolarisDatabase =
            Room.databaseBuilder(context, PolarisDatabase::class.java, "polaris.db").build()
    }
}
