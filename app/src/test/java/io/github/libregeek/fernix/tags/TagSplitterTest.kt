// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSplitterTest {

    private val defaults = TagSplitter.DEFAULT_SEPARATORS

    @Test
    fun splitsOnEverySeparator_trimsParts_keepsOrder() {
        assertEquals(listOf("Simon", "Garfunkel"), TagSplitter.split("Simon; Garfunkel", defaults))
        assertEquals(listOf("Rock", "Pop", "Soul"), TagSplitter.split("Rock/Pop , Soul", defaults))
    }

    @Test
    fun emptyPartsAreDropped_andRepeatsKeptOnceIgnoringCase() {
        assertEquals(listOf("A", "B"), TagSplitter.split(";A;;B;", defaults))
        assertEquals(listOf("Ann", "Bob"), TagSplitter.split("Ann; ann; Bob; ANN", defaults))
    }

    @Test
    fun aCustomSeparatorKeepsItsSpaces() {
        assertEquals(listOf("Ann", "Bob"), TagSplitter.split("Ann feat. Bob", listOf(" feat. ")))
        // Without the surrounding spaces it wouldn't match inside a word.
        assertEquals(listOf("Defeat. X"), TagSplitter.split("Defeat. X", listOf(" feat. ")))
    }

    @Test
    fun onlyTheChosenSeparatorsSplit_soAcDcStaysWholeWithoutTheSlash() {
        assertEquals(listOf("AC/DC"), TagSplitter.split("AC/DC", listOf(";", ",")))
        assertEquals(listOf("AC", "DC"), TagSplitter.split("AC/DC", listOf("/")))
    }

    @Test
    fun containsIgnoresEmptySeparators() {
        assertTrue(TagSplitter.contains("A;B", defaults))
        assertFalse(TagSplitter.contains("A and B", defaults))
        assertFalse(TagSplitter.contains("anything", listOf("")))
        assertFalse(TagSplitter.contains("anything", emptyList()))
    }

    @Test
    fun noSeparatorsLeavesTheTrimmedValue() {
        assertEquals(listOf("Simon; Garfunkel"), TagSplitter.split("Simon; Garfunkel", emptyList()))
    }

    @Test
    fun titleAlbumAndDateCanNotBeSplit() {
        assertEquals(setOf("TITLE", "ALBUM", "DATE"), TagSplitter.UNSPLITTABLE_KEYS)
    }
}
