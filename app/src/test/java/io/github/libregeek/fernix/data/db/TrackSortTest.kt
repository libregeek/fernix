// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSortTest {

    @Test
    fun encodeAndDecode_roundTrip() {
        val sort = TrackSort(listOf(SortLevel(SortField.YEAR, false), SortLevel(SortField.ALBUM), SortLevel(SortField.TRACK, false)))
        assertEquals("YEAR:D,ALBUM:A,TRACK:D", sort.encode())
        assertEquals(sort, TrackSort.decode(sort.encode()))
    }

    @Test
    fun decode_dropsUnknownRepeatedAndSurplusLevels() {
        assertEquals(TrackSort.DEFAULT, TrackSort.decode(null))
        assertEquals(TrackSort.DEFAULT, TrackSort.decode("NOPE:A"))
        assertEquals(listOf(SortLevel(SortField.YEAR)), TrackSort.decode("YEAR:A,YEAR:D,NOPE:A").levels)
        assertEquals(TrackSort.MAX_LEVELS, TrackSort.decode("TITLE:A,ALBUM:A,YEAR:A,TRACK:A,DURATION:A,DATE_ADDED:A").levels.size)
    }

    @Test
    fun onlyTitleAscending_isTheDefault() {
        assertTrue(TrackSort.DEFAULT.isDefault)
        assertFalse(TrackSort(listOf(SortLevel(SortField.TITLE, false))).isDefault)
        assertFalse(TrackSort.YEAR_ALBUM_TRACK.isDefault)
    }
}
