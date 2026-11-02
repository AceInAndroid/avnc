/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.targetContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardHistoryTest {
    private val history = ClipboardHistory(targetContext)
    @Before fun clearBefore() = history.clear()
    @After fun clearAfter() = history.clear()

    @Test fun boundsDeduplicatesMovesToFrontAndPersists() {
        repeat(25) { history.record("item-$it", sensitive = false, time = it.toLong()) }
        assertEquals(20, history.entries().size)
        assertEquals("item-24", history.entries().first().text)
        history.record("item-10", sensitive = false, time = 99)
        val reloaded = ClipboardHistory(targetContext).entries()
        assertEquals(20, reloaded.size)
        assertEquals("item-10", reloaded.first().text)
        assertEquals(1, reloaded.count { it.text == "item-10" })
        assertEquals(99L, reloaded.first().time)
    }

    @Test fun sensitiveOversizedAndEmptyTextAreNeverSaved() {
        history.record("password", sensitive = true)
        history.record("", sensitive = false)
        history.record("界".repeat(ClipboardHistory.MAX_TEXT_BYTES / 3 + 1), sensitive = false)
        assertTrue(history.entries().isEmpty())
        assertTrue(ClipboardHistory.accepts("x".repeat(ClipboardHistory.MAX_TEXT_BYTES)))
        assertFalse(ClipboardHistory.accepts("x".repeat(ClipboardHistory.MAX_TEXT_BYTES + 1)))
    }

    @Test fun totalSizeIsBoundedAndDeleteAndClearAreDurable() {
        repeat(20) { history.record(it.toString() + "x".repeat(ClipboardHistory.MAX_TEXT_BYTES - 2), sensitive = false) }
        val entries = history.entries()
        assertTrue(entries.sumOf { it.text.toByteArray(Charsets.UTF_8).size } <= 512 * 1024)
        assertTrue(entries.size < 20)
        history.remove(entries.first().id)
        assertFalse(ClipboardHistory(targetContext).entries().any { it.id == entries.first().id })
        history.clear()
        assertTrue(ClipboardHistory(targetContext).entries().isEmpty())
    }
}
