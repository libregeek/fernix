// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TagValuesTest {

    @Test
    fun joinThenSplit_roundTrips() {
        val values = listOf("Simon", "Garfunkel", "AC/DC")
        assertEquals(values, TagValues.split(TagValues.join(values)))
    }

    @Test
    fun join_usesUnitSeparator_neverNul() {
        val joined = TagValues.join(listOf("A", "B"))
        assertEquals("A\u001FB", joined)
        assertFalse(joined.contains('\u0000'))
    }

    @Test
    fun emptyColumn_hasNoValues() {
        assertEquals(emptyList<String>(), TagValues.split(""))
        assertEquals("", TagValues.join(emptyList()))
    }

    @Test
    fun singleValue_isKeptWhole() {
        assertEquals(listOf("A; B, C"), TagValues.split("A; B, C"))
    }

    @Test
    fun nonBmpCharacters_surviveRoundTrip() {
        val values = listOf("Müsic 🎵", "日本語")
        assertEquals(values, TagValues.split(TagValues.join(values)))
    }

    @Test
    fun display_joinsWithSemicolon() {
        assertEquals("A; B", TagValues.display(TagValues.join(listOf("A", "B"))))
        assertEquals("", TagValues.display(""))
    }
}
