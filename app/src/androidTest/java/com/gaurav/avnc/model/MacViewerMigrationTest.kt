/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.model

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.model.db.MainDb
import com.gaurav.avnc.targetContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MacViewerMigrationTest {
    private val dbName = "mac-viewer-migration-${UUID.randomUUID()}"
    @get:Rule val helper = MigrationTestHelper(instrumentation, MainDb::class.java)
    @After fun deleteDatabase() { targetContext.deleteDatabase(dbName) }

    private fun createVersion8Profile(): ContentValues {
        val schema = instrumentation.context.assets.open("com.gaurav.avnc.model.db.MainDb/8.json")
                .bufferedReader().use { JSONObject(it.readText()) }
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        val table = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == "profiles" }
        val fields = table.getJSONArray("fields")
        val values = ContentValues()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            val column = field.getString("columnName")
            when (field.getString("affinity")) {
                "TEXT" -> values.put(column, "legacy-$column")
                "INTEGER" -> values.put(column, 1L)
                "REAL" -> values.put(column, 1.25)
                else -> error("Unexpected column $column")
            }
        }
        values.put("ID", 67L)
        values.put("imageQuality", 7)
        values.put("useRawEncoding", 1)
        values.put("remotePlatform", "mac")
        values.put("remoteInputOverrides", RemoteInputOverrides(pointerSpeed = 1.8f).encode())
        helper.createDatabase(dbName, 8).use {
            assertEquals(67L, it.insert("profiles", SQLiteDatabase.CONFLICT_ABORT, values))
        }
        return values
    }

    @Test fun migrationPreservesEveryExistingFieldAndLegacyQuality() {
        val oldValues = createVersion8Profile()
        helper.runMigrationsAndValidate(dbName, 9, true).use { db ->
            db.query("SELECT * FROM profiles WHERE ID = 67").use { cursor ->
                assertTrue(cursor.moveToFirst())
                for ((column, expected) in oldValues.valueSet()) {
                    val index = cursor.getColumnIndexOrThrow(column)
                    when (expected) {
                        is String -> assertEquals(column, expected, cursor.getString(index))
                        is Double -> assertEquals(column, expected, cursor.getDouble(index), 0.0)
                        is Number -> assertEquals(column, expected.toLong(), cursor.getLong(index))
                    }
                }
                assertEquals("legacy", cursor.getString(cursor.getColumnIndexOrThrow("streamingPreset")))
                assertEquals("single", cursor.getString(cursor.getColumnIndexOrThrow("displayViewMode")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("selectedDisplayId")))
                assertEquals("{}", cursor.getString(cursor.getColumnIndexOrThrow("manualDisplayLayout")))
                assertEquals("shortcut", cursor.getString(cursor.getColumnIndexOrThrow("dockActivation")))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test fun migratedProfileCanPersistNewViewerChoices() = runBlocking {
        createVersion8Profile()
        helper.runMigrationsAndValidate(dbName, 9, true).close()
        val layout = RemoteDisplayLayout(200, 100,
                listOf(RemoteDisplay("custom", "Left", 0, 0, 100, 100)), "manual").encode()
        val first = Room.databaseBuilder(targetContext, MainDb::class.java, dbName).build()
        try {
            val profile = requireNotNull(first.serverProfileDao.getByID(67))
            assertEquals("legacy", profile.streamingPreset)
            profile.streamingPreset = "hd"
            profile.displayViewMode = "all"
            profile.selectedDisplayId = "custom"
            profile.manualDisplayLayout = layout
            profile.dockActivation = "bottom"
            first.serverProfileDao.update(profile)
        } finally { first.close() }
        val second = Room.databaseBuilder(targetContext, MainDb::class.java, dbName).build()
        try {
            val profile = requireNotNull(second.serverProfileDao.getByID(67))
            assertEquals("hd", profile.streamingPreset)
            assertEquals("all", profile.displayViewMode)
            assertEquals("custom", profile.selectedDisplayId)
            assertEquals(layout, profile.manualDisplayLayout)
            assertEquals("bottom", profile.dockActivation)
            assertEquals(7, profile.imageQuality)
            assertTrue(profile.useRawEncoding)
        } finally { second.close() }
    }
}
