// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.art

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cache(maxBytes: Long = 1000) = ThumbnailCache(File(folder.root, "thumbnails"), maxBytes)

    private fun bytes(n: Int) = ByteArray(n) { 1 }

    @Test
    fun putThenGet_returnsTheSameBytes_andAnUnknownKeyIsNull() {
        val cache = cache()
        assertNull(cache.get("album-1"))
        cache.put("album-1", bytes(10))
        assertEquals(10L, cache.get("album-1")!!.length())
        assertNull(cache.get("album-2"))
    }

    @Test
    fun aNoneMarker_isRemembered_andIsNotAThumbnail() {
        val cache = cache()
        assertFalse(cache.hasNone("album-1"))
        cache.putNone("album-1")
        assertTrue(cache.hasNone("album-1"))
        assertNull(cache.get("album-1"))
    }

    @Test
    fun overTheCap_theOldestFilesAreDeletedFirst() {
        val cache = cache(maxBytes = 250)
        val now = System.currentTimeMillis()
        cache.put("a", bytes(100)).setLastModified(now - 3000)
        cache.put("b", bytes(100)).setLastModified(now - 2000)
        cache.put("c", bytes(100)) // 300 bytes in all: over the cap, so the oldest goes
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertTrue(cache.size() <= 250)
    }

    @Test
    fun readingAThumbnail_keepsItFromBeingEvicted() {
        val cache = cache(maxBytes = 250)
        val now = System.currentTimeMillis()
        cache.put("old", bytes(100)).setLastModified(now - 5000)
        cache.put("newer", bytes(100)).setLastModified(now - 4000)
        assertNotNull(cache.get("old")) // used just now: no longer the oldest
        cache.put("fresh", bytes(100))
        assertNotNull(cache.get("old"))
        assertNull(cache.get("newer"))
    }

    @Test
    fun clear_removesEverything_includingMarkers_andTheCacheStillWorksAfterwards() {
        val cache = cache()
        cache.put("a", bytes(10))
        cache.putNone("b")
        cache.clear()
        assertNull(cache.get("a"))
        assertFalse(cache.hasNone("b"))
        assertEquals(0L, cache.size())
        cache.put("c", bytes(5))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun anAbsentFolderIsFine() {
        val cache = ThumbnailCache(File(folder.root, "never/created"), 100)
        assertNull(cache.get("x"))
        assertEquals(0L, cache.size())
        cache.clear()
    }
}
