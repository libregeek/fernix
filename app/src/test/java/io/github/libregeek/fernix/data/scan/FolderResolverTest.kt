// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import io.github.libregeek.fernix.data.db.FolderDao
import io.github.libregeek.fernix.data.db.FolderEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderResolverTest {

    private class FakeFolderDao : FolderDao {
        val rows = mutableListOf<FolderEntity>()
        var lookups = 0

        override suspend fun insert(folder: FolderEntity): Long {
            val id = rows.size + 1L
            rows += folder.copy(id = id)
            return id
        }

        override suspend fun byPath(path: String): FolderEntity? {
            lookups++
            return rows.firstOrNull { it.path == path }
        }

        override suspend fun byId(id: Long) = rows.firstOrNull { it.id == id }

        override suspend fun volumes() = rows.filter { it.parentId == null }
        override suspend fun childrenPage(parentId: Long, afterName: String, afterId: Long, limit: Int) =
            rows.filter { it.parentId == parentId }
    }

    @Test
    fun createsAncestorChain_withVolumeAsRoot() = runBlocking {
        val dao = FakeFolderDao()
        val id = FolderResolver(dao).idFor("external_primary/Music/Rock/Live")

        assertEquals(listOf("external_primary", "Music", "Rock", "Live"), dao.rows.map { it.name })
        assertEquals(
            listOf("external_primary", "external_primary/Music", "external_primary/Music/Rock", "external_primary/Music/Rock/Live"),
            dao.rows.map { it.path },
        )
        assertNull(dao.rows[0].parentId)
        assertEquals(listOf(null, 1L, 2L, 3L), dao.rows.map { it.parentId })
        assertEquals(4L, id)
    }

    @Test
    fun siblingsShareAncestors() = runBlocking {
        val dao = FakeFolderDao()
        val resolver = FolderResolver(dao)
        resolver.idFor("external_primary/Music/Rock")
        resolver.idFor("external_primary/Music/Jazz")

        assertEquals(4, dao.rows.size)
        assertEquals(dao.rows[1].id, dao.rows[3].parentId)
    }

    @Test
    fun volumeRootFile_isTheVolumeFolder() = runBlocking {
        val dao = FakeFolderDao()
        val id = FolderResolver(dao).idFor("1234-5678")
        assertEquals(1L, id)
        assertEquals("1234-5678", dao.rows.single().name)
    }

    @Test
    fun cachedPath_doesNotTouchTheDatabaseAgain() = runBlocking {
        val dao = FakeFolderDao()
        val resolver = FolderResolver(dao)
        val first = resolver.idFor("external_primary/Music")
        val lookups = dao.lookups
        assertEquals(first, resolver.idFor("external_primary/Music"))
        assertEquals(lookups, dao.lookups)
    }

    @Test
    fun existingFolders_areReusedAcrossScans() = runBlocking {
        val dao = FakeFolderDao()
        val first = FolderResolver(dao).idFor("external_primary/Music/Rock")
        val second = FolderResolver(dao).idFor("external_primary/Music/Rock")
        assertEquals(first, second)
        assertEquals(3, dao.rows.size)
    }
}
