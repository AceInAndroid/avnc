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
class RemoteInputMigrationTest {
    // A separate database avoids touching installed connection profiles.
    private val dbName = "remote-input-migration-${UUID.randomUUID()}"

    @get:Rule val helper = MigrationTestHelper(instrumentation, MainDb::class.java)

    @After fun deleteTestDatabase() {
        targetContext.deleteDatabase(dbName)
    }

    private fun createLegacyProfile(): ContentValues {
        val schema = instrumentation.context.assets
                .open("com.gaurav.avnc.model.db.MainDb/7.json")
                .bufferedReader().use { JSONObject(it.readText()) }
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        val table = (0 until entities.length()).map { entities.getJSONObject(it) }
                .first { it.getString("tableName") == "profiles" }
        val fields = table.getJSONArray("fields")
        val values = ContentValues()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            val column = field.getString("columnName")
            when (field.getString("affinity")) {
                "TEXT" -> values.put(column, "legacy-$column")
                "INTEGER" -> values.put(column, 1L)
                "REAL" -> values.put(column, 1.25)
                else -> error("Unexpected legacy profile column: $column")
            }
        }
        values.put("ID", 41L)
        values.put("host", "192.0.2.41")
        values.put("port", 5902)
        values.put("flags", 12L)
        values.put("gestureStyle", "touchpad")
        values.put("screenOrientation", "landscape")
        helper.createDatabase(dbName, 7).use {
            assertEquals(41L, it.insert("profiles", SQLiteDatabase.CONFLICT_ABORT, values))
        }
        return values
    }

    @Test fun migrationAddsDefaultsAndPreservesEveryLegacyColumn() {
        val legacy = createLegacyProfile()
        helper.runMigrationsAndValidate(dbName, 8, true).use { db ->
            db.query("SELECT * FROM profiles WHERE ID = 41").use { cursor ->
                assertTrue(cursor.moveToFirst())
                for ((column, expected) in legacy.valueSet()) {
                    val index = cursor.getColumnIndexOrThrow(column)
                    when (expected) {
                        is String -> assertEquals(column, expected, cursor.getString(index))
                        is Double -> assertEquals(column, expected, cursor.getDouble(index), 0.0)
                        is Number -> assertEquals(column, expected.toLong(), cursor.getLong(index))
                        else -> error("Unexpected fixture value: $column")
                    }
                }
                assertEquals("auto", cursor.getString(cursor.getColumnIndexOrThrow("remotePlatform")))
                assertEquals("{}", cursor.getString(cursor.getColumnIndexOrThrow("remoteInputOverrides")))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test fun migratedProfileAcceptsAndPersistsPerConnectionSettings() = runBlocking {
        createLegacyProfile()
        helper.runMigrationsAndValidate(dbName, 8, true).close()
        val overrides = RemoteInputOverrides(pointerSpeed = 1.8f, horizontalSpeed = .7f,
                invertVertical = true, threeFinger = "system").encode()
        val first = Room.databaseBuilder(targetContext, MainDb::class.java, dbName).build()
        try {
            val profile = requireNotNull(first.serverProfileDao.getByID(41L))
            assertEquals("auto", profile.remotePlatform)
            assertEquals("{}", profile.remoteInputOverrides)
            profile.remotePlatform = "mac"
            profile.remoteInputOverrides = overrides
            first.serverProfileDao.update(profile)
        } finally {
            first.close()
        }
        val second = Room.databaseBuilder(targetContext, MainDb::class.java, dbName).build()
        try {
            val reloaded = requireNotNull(second.serverProfileDao.getByID(41L))
            assertEquals("mac", reloaded.remotePlatform)
            assertEquals(overrides, reloaded.remoteInputOverrides)
            assertEquals("192.0.2.41", reloaded.host)
            assertEquals("touchpad", reloaded.gestureStyle)
            assertEquals(12L, reloaded.flags)
        } finally {
            second.close()
        }
    }
}
