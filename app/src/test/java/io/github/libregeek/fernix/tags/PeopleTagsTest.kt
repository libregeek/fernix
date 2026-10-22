// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTagsTest {

    @Test
    fun plainKeys_arePeopleKeys() {
        listOf("ARTIST", "ALBUMARTIST", "COMPOSER", "LYRICIST", "PERFORMER", "DJMIXER")
            .forEach { assertTrue(it, PeopleTags.isPeopleKey(it)) }
    }

    @Test
    fun performerWithInstrument_isPeopleKey() {
        assertTrue(PeopleTags.isPeopleKey("PERFORMER:GUITAR"))
        assertTrue(PeopleTags.isPeopleKey("PERFORMER:BACKING VOCALS"))
    }

    @Test
    fun otherKeys_areNot() {
        listOf("TITLE", "ALBUM", "GENRE", "BPM", "MUSICBRAINZ_TRACKID", "PERFORMERS", "")
            .forEach { assertFalse(it, PeopleTags.isPeopleKey(it)) }
    }

    @Test
    fun specHasFifteenPlainKeys() {
        assertEquals(15, PeopleTags.KEYS.size)
    }
}
