// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class CreditsTest {

    private fun joined(vararg v: String) = TagValues.join(v.toList())

    @Test
    fun `composers come first then lyricists then artists`() {
        assertEquals(listOf("C", "L", "A"), Credits.people("C", "L", "A"))
    }

    @Test
    fun `a name that repeats is kept once in its first place, ignoring case`() {
        assertEquals(listOf("Ilaiyaraaja", "Vaali", "YESUDAS"), Credits.people(joined("Ilaiyaraaja"), joined("Vaali", "ilaiyaraaja"), joined("YESUDAS", "Vaali")))
    }

    @Test
    fun `the lock screen shows at most six people`() {
        val line = Credits.lockScreenLine("Album", joined("1", "2", "3"), joined("4", "5"), joined("6", "7", "8"))
        assertEquals("Album · 1; 2; 3; 4; 5; 6", line)
    }

    @Test
    fun `no album and no people give no line`() {
        assertNull(Credits.lockScreenLine("", "", "", ""))
        assertNull(Credits.line(emptyList()))
    }

    @Test
    fun `the mini player has no limit`() {
        assertEquals(8, Credits.people(joined("1", "2", "3", "4"), joined("5", "6"), joined("7", "8")).size)
    }
}
