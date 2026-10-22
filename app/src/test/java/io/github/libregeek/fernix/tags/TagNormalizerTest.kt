// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TagNormalizerTest {

    @Test
    fun valuesAreTrimmed_andEmptyOnesDropped() {
        val out = TagNormalizer.normalize(mapOf("ARTIST" to listOf("  A ", "", "   ", "B")))
        assertEquals(mapOf("ARTIST" to listOf("A", "B")), out)
    }

    @Test
    fun keyWithoutValues_isRemoved() {
        assertEquals(emptyMap<String, List<String>>(), TagNormalizer.normalize(mapOf("GENRE" to listOf(" ", ""))))
    }

    @Test
    fun excludedKeys_areDropped() {
        val out = TagNormalizer.normalize(
            mapOf("LYRICS" to listOf("la la"), "ACOUSTID_FINGERPRINT" to listOf("AQAA"), "TITLE" to listOf("T")),
        )
        assertEquals(mapOf("TITLE" to listOf("T")), out)
    }

    @Test
    fun longValues_areDropped_at512Characters() {
        val ok = "x".repeat(512)
        val tooLong = "x".repeat(513)
        val out = TagNormalizer.normalize(mapOf("COMMENT" to listOf(ok, tooLong)))
        assertEquals(mapOf("COMMENT" to listOf(ok)), out)
    }

    @Test
    fun repeatedValue_ignoringCase_isKeptOnce_inFirstPosition() {
        val out = TagNormalizer.normalize(mapOf("ARTIST" to listOf("Ac/Dc", "Other", "AC/DC")))
        assertEquals(listOf("Ac/Dc", "Other"), out["ARTIST"])
    }

    @Test
    fun keysAreUppercased_andOrderKept() {
        val out = TagNormalizer.normalize(linkedMapOf("title" to listOf("T"), "Artist" to listOf("B", "A")))
        assertEquals(listOf("TITLE", "ARTIST"), out.keys.toList())
        assertEquals(listOf("B", "A"), out["ARTIST"])
    }

    @Test
    fun sameKeyInDifferentCase_isMerged() {
        val out = TagNormalizer.normalize(linkedMapOf("artist" to listOf("A"), "ARTIST" to listOf("B")))
        assertEquals(mapOf("ARTIST" to listOf("A", "B")), out)
        assertFalse(out.containsKey("artist"))
    }

    @Test
    fun originalMap_isNotModified() {
        val raw = mapOf("ARTIST" to listOf(" A "))
        TagNormalizer.normalize(raw)
        assertEquals(listOf(" A "), raw["ARTIST"])
    }
}
