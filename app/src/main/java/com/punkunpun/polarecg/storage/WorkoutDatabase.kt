package com.punkunpun.polarecg.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The workouts database, **separate from [PolarDatabase]** on purpose: workouts are the owner's own record of rides and
 * must not be touched by the heart-data retention, export or clean-ups of `polar.db`. Version 1: `workout`.
 * Its table layout is exported to `app/schemas/` on build (commit it); a later change is a new [version] with a migration.
 */
@Database(entities = [WorkoutEntity::class], version = 1, exportSchema = true)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        const val FILE_NAME = "workouts.db"

        fun open(context: Context): WorkoutDatabase =
            Room.databaseBuilder(context, WorkoutDatabase::class.java, FILE_NAME).build()
    }
}
