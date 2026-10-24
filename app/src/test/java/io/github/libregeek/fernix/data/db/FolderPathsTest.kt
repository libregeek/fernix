// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPathsTest {

    private fun inSubtree(path: String, candidate: String): Boolean {
        val (low, high) = FolderPaths.subtreeRange(path)
        return candidate == path || (candidate >= low && candidate < high)
    }

    @Test
    fun rangeBoundsAreSlashAndZero() {
        assertEquals("external_primary/Music/" to "external_primary/Music0", FolderPaths.subtreeRange("external_primary/Music"))
    }

    @Test
    fun includesDescendantsAtAnyDepth() {
        assertTrue(inSubtree("v/Music", "v/Music"))
        assertTrue(inSubtree("v/Music", "v/Music/Rock"))
        assertTrue(inSubtree("v/Music", "v/Music/Rock/Live"))
    }

    @Test
    fun excludesSiblingsWithTheSamePrefix() {
        assertFalse(inSubtree("v/Music", "v/Music2"))
        assertFalse(inSubtree("v/Music", "v/Music 2/Rock"))
        assertFalse(inSubtree("v/Music", "v/Musi"))
        assertFalse(inSubtree("v/Music", "v/Other/Music"))
    }
}
