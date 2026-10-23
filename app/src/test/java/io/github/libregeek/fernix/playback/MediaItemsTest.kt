// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import io.github.libregeek.fernix.tags.TagValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaItemsTest {

    @Test
    fun mediaId_roundTripsTheTrackId() {
        assertEquals(42L, MediaItems.trackId(MediaItems.mediaId(42)))
    }

    @Test
    fun foreignMediaId_isNotATrack() {
        assertNull(MediaItems.trackId("not-ours"))
        assertNull(MediaItems.trackId(""))
    }

    @Test
    fun multiValueColumns_areShownJoinedWithSemicolons() {
        assertEquals("A; B", MediaItems.display(TagValues.join(listOf("A", "B"))))
        assertEquals("A", MediaItems.display("A"))
        assertNull(MediaItems.display(""))
    }
}
