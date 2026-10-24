// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TestLibrary
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.data.scan.MediaRowIterator
import io.github.libregeek.fernix.data.scan.MediaSource
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.ui.folders.FoldersViewModel
import io.github.libregeek.fernix.ui.scan.FolderChooserViewModel
import io.github.libregeek.fernix.ui.tracks.PersonChip
import io.github.libregeek.fernix.ui.tracks.TagChip
import io.github.libregeek.fernix.ui.tracks.TracksViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The state worth keeping when Android kills the process (spec/architecture.md §9): the saved-state
 * handle is copied into a brand-new handle, as the system does, and a new view model must come up with
 * the same search, filters, folder and ticked folders.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var db: FernixDatabase
    private lateinit var lib: TestLibrary

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, FernixDatabase::class.java).build()
        lib = TestLibrary(db)
    }

    @After
    fun close() = db.close()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    /** What the system would hand to the next process: the same contents in a fresh handle. */
    private fun afterProcessDeath(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })

    private fun tracks(handle: SavedStateHandle, person: Long = 0, tagKey: Long = 0): TracksViewModel {
        lateinit var vm: TracksViewModel
        instrumentation.runOnMainSync {
            vm = TracksViewModel(db, TrackSearch(db), PlaybackController(instrumentation.targetContext), handle, person, tagKey)
        }
        return vm
    }

    @Test
    fun tracks_comesBackWithTheSameTextAndFilters_andTheSameList() = runBlocking {
        val f = lib.folder()
        val a = lib.track("Alpha one", f)
        val b = lib.track("Alpha two", f)
        lib.track("Bravo", f)
        lib.tag(a, "GENRE", "Rock"); lib.tag(b, "GENRE", "Rock")
        lib.tag(a, "ARTIST", "Ann")
        lib.refreshSummaries(PeopleTags.KEYS)
        val genre = lib.keyId("GENRE")
        val rock = lib.valueId("Rock")
        val ann = lib.valueId("Ann")

        val handle = SavedStateHandle()
        val before = tracks(handle, person = rock, tagKey = genre) // starts with GENRE: Rock
        waitFor("the genre list") { before.state.value.results.size == 2 && !before.state.value.loading }
        instrumentation.runOnMainSync { before.setText("alpha"); before.setPerson(PersonChip(ann, "Ann")) }
        waitFor("narrowed") { before.state.value.results.map { it.track.title } == listOf("Alpha one") && !before.state.value.loading }

        val after = tracks(afterProcessDeath(handle)) // a new process: no initial filter is given
        waitFor("the restored list") { after.state.value.results.size == 1 && !after.state.value.loading }
        assertEquals("alpha", after.state.value.text)
        assertEquals(PersonChip(ann, "Ann"), after.state.value.person)
        assertEquals(listOf(TagChip(genre, "GENRE", rock, "Rock")), after.state.value.chips)
        assertEquals(listOf("Alpha one"), after.state.value.results.map { it.track.title })
    }

    @Test
    fun tracks_afterClearAll_comesBackClear_notWithTheOldFilters() = runBlocking {
        val f = lib.folder()
        lib.track("Alpha", f)
        lib.track("Bravo", f)
        val handle = SavedStateHandle()
        val before = tracks(handle)
        instrumentation.runOnMainSync { before.setText("alpha") }
        waitFor("narrowed") { before.state.value.results.size == 1 && !before.state.value.loading }
        instrumentation.runOnMainSync { before.clearAll() }
        waitFor("everything") { before.state.value.results.size == 2 }

        val after = tracks(afterProcessDeath(handle))
        waitFor("everything again") { after.state.value.results.size == 2 }
        assertEquals("", after.state.value.text)
        assertNull(after.state.value.person)
        assertEquals(emptyList<TagChip>(), after.state.value.chips)
    }

    @Test
    fun tracks_aSavedFilterWhoseValueIsGone_stillComesUpWithoutCrashing() = runBlocking {
        val f = lib.folder()
        lib.track("Alpha", f)
        val handle = SavedStateHandle(
            mapOf("text" to "", "person" to "9999\u001FNobody", "chips" to arrayListOf("1\u001FGENRE\u001F9999\u001FGone")),
        )
        val vm = tracks(handle)
        // The person is dropped as stale; the list still loads (the tag chip simply matches nothing).
        waitFor("a result for the stale filters") { !vm.state.value.loading && vm.state.value.person == null }
    }

    @Test
    fun folders_returnToTheOpenFolder() = runBlocking {
        val root = lib.folder("Music")
        val rock = lib.folder("Rock", root)
        val live = lib.folder("Live", rock)
        lib.track("In live", live)
        val playback = PlaybackController(instrumentation.targetContext)

        val handle = SavedStateHandle()
        lateinit var before: FoldersViewModel
        instrumentation.runOnMainSync { before = FoldersViewModel(db, playback, handle) }
        waitFor("the top level") { !before.state.value.loading && before.state.value.path.isNotEmpty() }
        val folders = db.folderDao()
        instrumentation.runOnMainSync {
            before.open(runBlocking { folders.byId(rock)!! })
            before.open(runBlocking { folders.byId(live)!! })
        }
        waitFor("the live folder") { before.state.value.path.map { it.id } == listOf(root, rock, live) && before.state.value.tracks.isNotEmpty() }

        lateinit var after: FoldersViewModel
        instrumentation.runOnMainSync { after = FoldersViewModel(db, playback, afterProcessDeath(handle)) }
        waitFor("the restored folder") { after.state.value.path.map { it.id } == listOf(root, rock, live) && after.state.value.tracks.isNotEmpty() }
        assertEquals(listOf("In live"), after.state.value.tracks.map { it.title })
    }

    @Test
    fun folders_aFolderRemovedByAScan_dropsOutOfTheRestoredPath() = runBlocking {
        val root = lib.folder("Music")
        val gone = lib.folder("Gone", root)
        val handle = SavedStateHandle(mapOf("path" to arrayListOf(root, gone, 424242L)))
        db.openHelper.writableDatabase.execSQL("DELETE FROM folders WHERE id = $gone")
        lateinit var vm: FoldersViewModel
        instrumentation.runOnMainSync { vm = FoldersViewModel(db, PlaybackController(instrumentation.targetContext), handle) }
        waitFor("the path that is left") { !vm.state.value.loading && vm.state.value.path.map { it.id } == listOf(root) }
    }

    @Test
    fun folderChooser_keepsTheTickedFolders() {
        val media = object : MediaSource {
            override fun mountedVolumes() = setOf("external_primary")
            override fun folders(volumes: Set<String>) = setOf("external_primary/Music/Rock", "external_primary/Music/Jazz")
            override fun query(volumes: Set<String>): MediaRowIterator = throw UnsupportedOperationException()
        }
        val handle = SavedStateHandle()
        lateinit var before: FolderChooserViewModel
        instrumentation.runOnMainSync { before = FolderChooserViewModel(media, emptySet(), handle) }
        waitFor("the tree") { !before.state.value.loading }
        instrumentation.runOnMainSync { before.toggleSelected("external_primary/Music/Jazz") }

        lateinit var after: FolderChooserViewModel
        instrumentation.runOnMainSync { after = FolderChooserViewModel(media, emptySet(), afterProcessDeath(handle)) }
        waitFor("the tree again") { !after.state.value.loading }
        assertEquals(setOf("external_primary/Music/Jazz"), after.state.value.selected)
    }
}
