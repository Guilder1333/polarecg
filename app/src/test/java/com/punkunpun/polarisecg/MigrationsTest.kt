package com.punkunpun.polarisecg

import com.punkunpun.polarisecg.storage.MIGRATION_1_2_SQL
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationsTest {
    /** The `CREATE TABLE` Room itself wrote for [table] in the exported schema of [version]. */
    private fun roomSql(version: Int, table: String): String {
        val file = File("schemas/com.punkunpun.polarisecg.storage.PolarisDatabase/$version.json")
        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            if (e.getString("tableName") == table) return e.getString("createSql").replace("\${TABLE_NAME}", table)
        }
        error("no table $table in $file")
    }

    @Test
    fun theVersion2MigrationCreatesExactlyWhatRoomExpects() {
        assertEquals(listOf(roomSql(2, "minute_summary"), roomSql(2, "hrv_window")), MIGRATION_1_2_SQL)
    }
}
