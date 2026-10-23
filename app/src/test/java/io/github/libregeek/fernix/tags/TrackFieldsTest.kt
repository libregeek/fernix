// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackFieldsTest {

    @Test
    fun fixedFields_areJoinedInOrder() {
        val fields = TrackFields.from(
            mapOf(
                "TITLE" to listOf("Song"),
                "ARTIST" to listOf("A", "B"),
                "COMPOSER" to listOf("C"),
                "LYRICIST" to listOf("L"),
                "ALBUM" to listOf("Album"),
                "DATE" to listOf("2021-05-03"),
            ),
        )
        assertEquals("Song", fields.title)
        assertEquals(TagValues.join(listOf("A", "B")), fields.artist)
        assertEquals("C", fields.composer)
        assertEquals("L", fields.lyricist)
        assertEquals("Album", fields.album)
        assertEquals("2021-05-03", fields.date)
        assertEquals(2021, fields.year)
    }

    @Test
    fun missingTags_areEmpty() {
        val fields = TrackFields.from(emptyMap())
        assertEquals("", fields.title)
        assertEquals("", fields.artist)
        assertNull(fields.trackNumber)
        assertNull(fields.discNumber)
        assertNull(fields.year)
        assertNull(fields.albumKey)
    }

    @Test
    fun trackAndDiscNumbers_acceptTotals() {
        val fields = TrackFields.from(mapOf("TRACKNUMBER" to listOf("3/12"), "DISCNUMBER" to listOf("2/2")))
        assertEquals(3, fields.trackNumber)
        assertEquals(2, fields.discNumber)
    }

    @Test
    fun leadingNumber_rejectsNonNumbers() {
        assertEquals(7, TrackFields.leadingNumber("07"))
        assertNull(TrackFields.leadingNumber("A1"))
        assertNull(TrackFields.leadingNumber("0"))
        assertNull(TrackFields.leadingNumber(""))
        assertNull(TrackFields.leadingNumber(null))
        assertNull(TrackFields.leadingNumber("123456"))
    }

    @Test
    fun year_parsesIsoDatesOnly() {
        assertEquals(1999, TrackFields.year("1999"))
        assertEquals(1999, TrackFields.year("1999-12"))
        assertEquals(1999, TrackFields.year("1999-12-31T23:59"))
        assertNull(TrackFields.year("99"))
        assertNull(TrackFields.year("19990"))
        assertNull(TrackFields.year("12/31/1999"))
        assertNull(TrackFields.year("0000"))
        assertNull(TrackFields.year(null))
    }

    @Test
    fun year_comesFromTheFirstDateValue() {
        assertEquals(2001, TrackFields.from(mapOf("DATE" to listOf("2001", "1999"))).year)
    }

    @Test
    fun albumKey_isFirstAlbumAndYear_orZero() {
        assertEquals("X" to 2001, TrackFields.from(mapOf("ALBUM" to listOf("X", "Y"), "DATE" to listOf("2001"))).albumKey)
        assertEquals("X" to 0, TrackFields.from(mapOf("ALBUM" to listOf("X"))).albumKey)
    }
}
