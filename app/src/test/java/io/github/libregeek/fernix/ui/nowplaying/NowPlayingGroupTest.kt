// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.nowplaying

import io.github.libregeek.fernix.data.db.PlayingTag
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingGroupTest {

    private fun tag(key: String, id: Long, value: String) = PlayingTag(key, id, value)

    @Test
    fun slotsKeepTheirOrder_andMissingTagsAreHidden() {
        val lines = NowPlayingViewModel.group(
            listOf("COMPOSER", "LYRICIST", "ARTIST"),
            listOf(tag("ARTIST", 1, "Ann"), tag("COMPOSER", 2, "Johnson")),
        )
        assertEquals(listOf("COMPOSER", "ARTIST"), lines.map { it.label })
    }

    @Test
    fun everyValueIsItsOwnEntry_inStoredOrder() {
        val lines = NowPlayingViewModel.group(
            listOf("ARTIST"),
            listOf(tag("ARTIST", 1, "Zed"), tag("ARTIST", 2, "Amy")),
        )
        assertEquals(listOf("Zed", "Amy"), lines.single().values.map { it.text })
    }

    @Test
    fun peopleKeysAreTappable_otherKeysAreNot() {
        val lines = NowPlayingViewModel.group(
            listOf("COMPOSER", "GENRE"),
            listOf(tag("COMPOSER", 1, "Johnson"), tag("GENRE", 9, "Rock")),
        )
        assertEquals(listOf(true, false), lines.map { it.values.single().isPerson })
    }

    @Test
    fun performerSlot_takesEveryInstrument_labelledByInstrument() {
        val lines = NowPlayingViewModel.group(
            listOf("PERFORMER", "ARTIST"),
            // As the query delivers them: by key, then by order within the key.
            listOf(
                tag("ARTIST", 1, "Ann"),
                tag("PERFORMER:DRUMS", 3, "Dee"),
                tag("PERFORMER:GUITAR", 2, "Gil"),
                tag("PERFORMER:GUITAR", 4, "Gus"),
            ),
        )
        assertEquals(listOf("DRUMS", "GUITAR", "ARTIST"), lines.map { it.label })
        assertEquals(listOf("Gil", "Gus"), lines[1].values.map { it.text })
        assertEquals(true, lines[1].values.all { it.isPerson })
    }

    @Test
    fun aPerformerKeyWithoutThePerformerSlot_isNotShown() {
        val lines = NowPlayingViewModel.group(listOf("ARTIST"), listOf(tag("PERFORMER:GUITAR", 2, "Gil")))
        assertEquals(emptyList<TagLine>(), lines)
    }
}
