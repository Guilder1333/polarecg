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

/**
 * Version 3 adds the `event` table and its index (P5.4). Written exactly as Room writes them (see `MigrationsTest`, which compares
 * with the schema Room exported to `app/schemas/.../3.json` at build time).
 */
internal val MIGRATION_2_3_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startUtcMs` INTEGER NOT NULL, " +
        "`endUtcMs` INTEGER NOT NULL, `type` TEXT NOT NULL, `beatCount` INTEGER NOT NULL, `detail` TEXT NOT NULL, `clipFile` TEXT)",
    "CREATE INDEX IF NOT EXISTS `index_event_startUtcMs` ON `event` (`startUtcMs`)",
)

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
    }
}

/**
 * Version 4 adds the `label` table and its index (P5.7), the one table that cannot be rebuilt from the raw files. Written exactly
 * as Room writes them (see `MigrationsTest`, which compares with `4.json`).
 */
internal val MIGRATION_3_4_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `label` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `beatUtcMs` INTEGER NOT NULL, " +
        "`label` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAtUtcMs` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_label_beatUtcMs` ON `label` (`beatUtcMs`)",
)

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_3_4_SQL.forEach { db.execSQL(it) }
    }
}
