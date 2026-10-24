// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.trackdetails

import io.github.libregeek.fernix.data.db.PlayingTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackDetailViewModelTest {

    @Test
    fun everyKeyIsOneBlock_withAllItsValuesInOrder_andPeopleKeysAreMarked() {
        val lines = TrackDetailViewModel.group(
            listOf(
                PlayingTag("ARTIST", 1, "Zed"),
                PlayingTag("ARTIST", 2, "Amy"),
                PlayingTag("BPM", 3, "111"),
                PlayingTag("PERFORMER:GUITAR", 4, "Gil"),
            ),
        )
        assertEquals(listOf("ARTIST", "BPM", "PERFORMER:GUITAR"), lines.map { it.label })
        assertEquals(listOf("Zed", "Amy"), lines[0].values.map { it.text })
        assertEquals(listOf(true, false, true), lines.map { it.values.first().isPerson })
    }

    @Test
    fun theKeyIdTravelsWithEveryValue_soAPillCanOpenTracksFilteredByIt() {
        val lines = TrackDetailViewModel.group(listOf(PlayingTag("GENRE", 9, "Rock", keyId = 4), PlayingTag("GENRE", 10, "Pop", keyId = 4)))
        assertEquals(listOf(4L, 4L), lines.single().values.map { it.keyId })
        assertEquals(listOf(9L, 10L), lines.single().values.map { it.valueId })
    }

    @Test
    fun formatComesFromTheExtension() {
        assertEquals("MP3", TrackDetailViewModel.formatOf("Song.MP3"))
        assertEquals("FLAC", TrackDetailViewModel.formatOf("a.flac"))
        assertEquals("Ogg Vorbis", TrackDetailViewModel.formatOf("a.ogg"))
        assertEquals("Opus", TrackDetailViewModel.formatOf("a.opus"))
        assertEquals("WAV", TrackDetailViewModel.formatOf("a.wav"))
        assertEquals("M4A", TrackDetailViewModel.formatOf("a.m4a"))
        assertNull(TrackDetailViewModel.formatOf("no-extension"))
    }
}
