// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TestLibrary
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tracks on a volume that is not mounted are found, marked, and kept out of the queue (spec §6 *Unmounted volumes*). */
@RunWith(AndroidJUnit4::class)
class AvailableVolumesTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var db: FernixDatabase

    @Before
    fun open() {
        db = TestLibrary.inMemoryDb(instrumentation.targetContext)
    }

    @After
    fun close() = db.close()

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out")
    }

    private fun row(id: Long, folderId: Long) =
        TrackRow(id = id, mediaStoreId = id, title = "T$id", album = "", artist = "", durationMs = 1000, folderId = folderId)

    @Test
    fun foldersOnAnUnmountedVolume_areUnavailable_andTheOthersAreNot() = runBlocking {
        val lib = TestLibrary(db)
        val mounted = MediaStore.getExternalVolumeNames(instrumentation.targetContext).first()
        val here = db.folderDao().insert(io.github.libregeek.fernix.data.db.FolderEntity(parentId = null, name = mounted, path = "$mounted/Music"))
        val gone = db.folderDao().insert(io.github.libregeek.fernix.data.db.FolderEntity(parentId = null, name = "ZZZZ-9999", path = "ZZZZ-9999/Music"))
        val volumes = AvailableVolumes(instrumentation.targetContext, db)
        volumes.refresh()
        waitFor { volumes.unavailableFolders.value.isNotEmpty() }

        assertEquals(setOf(gone), volumes.unavailableFolders.value)
        assertTrue(volumes.isAvailable(row(1, here)))
        assertFalse(volumes.isAvailable(row(2, gone)))
    }

    @Test
    fun aTrackOnAMissingVolume_isNotQueued_andTheUserIsTold() {
        lateinit var controller: PlaybackController
        instrumentation.runOnMainSync {
            controller = PlaybackController(instrumentation.targetContext) { it.folderId != 2L }
            controller.play(listOf(row(1, 2), row(2, 2)))
            controller.enqueue(listOf(row(3, 2)))
        }
        assertEquals(2, controller.state.value.unavailableCount)
        assertEquals(0, controller.state.value.queueSize)
    }
}
