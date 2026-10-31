package com.punkunpun.polarisecg.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/*
 * Schema steps (plan §8). Room checks the real file against the layout it was built with and refuses to open a database
 * that does not match, so every version step needs its SQL here, exactly as Room would create the tables: the unit test
 * `MigrationsTest` compares these statements with the exported schema in `app/schemas/`.
 * There is no destructive fallback: a database step that fails must be visible, not silently empty the owner's data.
 */

/** Version 2 adds the long-term tables `minute_summary` and `hrv_window` (P5.6). The `beat` table is unchanged. */
internal val MIGRATION_1_2_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `minute_summary` (`minuteUtcMs` INTEGER NOT NULL, `beats` INTEGER NOT NULL, `n` INTEGER NOT NULL, " +
        "`s` INTEGER NOT NULL, `v` INTEGER NOT NULL, `q` INTEGER NOT NULL, `vDoubt` INTEGER NOT NULL, `hrMeanBpm` REAL, " +
        "`hrMinBpm` REAL, `hrMaxBpm` REAL, `coverage` REAL NOT NULL, `motionMg` REAL, PRIMARY KEY(`minuteUtcMs`))",
    "CREATE TABLE IF NOT EXISTS `hrv_window` (`startUtcMs` INTEGER NOT NULL, `nn` INTEGER NOT NULL, `coverage` REAL NOT NULL, " +
        "`valid` INTEGER NOT NULL, `meanHrBpm` REAL, `sdnnMs` REAL, `rmssdMs` REAL, `pnn50Percent` REAL, `lfMs2` REAL, " +
        "`hfMs2` REAL, `lfHf` REAL, PRIMARY KEY(`startUtcMs`))",
)

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_SQL.forEach { db.execSQL(it) }
    }
}
