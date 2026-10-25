// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.TrackSort
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TestLibrary
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.ui.tracks.PersonChip
import io.github.libregeek.fernix.ui.tracks.TagChip
import io.github.libregeek.fernix.ui.tracks.TracksViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TracksViewModelTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var db: FernixDatabase
    private lateinit var lib: TestLibrary

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, FernixDatabase::class.java).build()
        lib = TestLibrary(db)
        Settings(instrumentation.targetContext).trackSort = TrackSort.DEFAULT
    }

    @After
    fun close() {
        db.close()
        Settings(instrumentation.targetContext).trackSort = TrackSort.DEFAULT
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out")
    }

    /**
     * Setting a filter (e.g. Search opened from Now Playing with a person) must list each track once. The
     * empty list asks for more rows at once, which used to start a second first-page load and show
     * every row twice, crashing the list on its duplicate keys.
     */
    @Test
    fun settingAFilter_listsEachTrackOnce_evenWhenTheListAsksForMoreAtOnce() = runBlocking {
        val f = lib.folder()
        val ids = listOf("A", "B", "C").map { lib.track(it, f) }
        ids.forEach { lib.tag(it, "COMPOSER", "Johnson") }
        lib.refreshSummaries(PeopleTags.KEYS)
        val person = lib.valueId("Johnson")

        lateinit var vm: TracksViewModel
        instrumentation.runOnMainSync {
            vm = TracksViewModel(db, TrackSearch(db), PlaybackController(instrumentation.targetContext), SavedStateHandle(), Settings(instrumentation.targetContext))
            // What the screen does: the filter is set, and in the same frame the still-empty list
            // asks for more rows, before the first page has come back from the database.
            vm.setPerson(PersonChip(person, "Johnson"))
            vm.loadMore()
            vm.loadMore()
        }
        waitFor { vm.state.value.results.size >= 3 && !vm.state.value.loading }
        Thread.sleep(300)
        instrumentation.runOnMainSync { vm.loadMore() }
        Thread.sleep(300)

        assertEquals(ids.size, vm.state.value.results.size)
        assertEquals(ids.size, vm.state.value.results.map { it.track.id }.toSet().size)
    }

    private fun waitFor(what: String, condition: () -> Boolean) = try {
        waitFor(condition)
    } catch (e: AssertionError) {
        throw AssertionError("Timed out waiting for $what")
    }

    private fun newViewModel(personId: Long = 0, tagKeyId: Long = 0): TracksViewModel {
        lateinit var vm: TracksViewModel
        instrumentation.runOnMainSync {
            vm = TracksViewModel(db, TrackSearch(db), PlaybackController(instrumentation.targetContext), SavedStateHandle(), Settings(instrumentation.targetContext), personId, tagKeyId)
        }
        return vm
    }

    @Test
    fun withNothingTypedAndNoFilter_listsEveryTrack_pageByPage() = runBlocking {
        val f = lib.folder()
        val total = io.github.libregeek.fernix.data.db.PAGE_SIZE + 30
        repeat(total) { lib.track("T%04d".format(it), f) }
        val vm = newViewModel()
        waitFor("the first page") { vm.state.value.results.size == io.github.libregeek.fernix.data.db.PAGE_SIZE }
        instrumentation.runOnMainSync { vm.loadMore() }
        waitFor("the second page") { vm.state.value.results.size == total }
        assertEquals(total, vm.state.value.results.map { it.track.id }.toSet().size)
        assertEquals(total, vm.allTracks().size)
        assertEquals(false, vm.state.value.noResults)
    }

    @Test
    fun clearAll_removesTheTextAndEveryFilter_andTheWholeLibraryIsListedAgain() = runBlocking {
        val f = lib.folder()
        val a = lib.track("Alpha", f)
        lib.track("Bravo", f)
        lib.tag(a, "ARTIST", "Ann")
        lib.refreshSummaries(PeopleTags.KEYS)
        val ann = lib.valueId("Ann")
        val artist = lib.keyId("ARTIST")
        val vm = newViewModel()
        waitFor("both tracks") { vm.state.value.results.size == 2 }

        instrumentation.runOnMainSync {
            vm.setPerson(PersonChip(ann, "Ann"))
            vm.setText("alp")
        }
        waitFor("the narrowed list") { vm.state.value.results.map { it.track.title } == listOf("Alpha") && !vm.state.value.loading }
        assertEquals(true, vm.state.value.hasFilter)

        instrumentation.runOnMainSync { vm.clearAll() }
        waitFor("everything again") { vm.state.value.results.size == 2 }
        assertEquals("", vm.state.value.text)
        assertEquals(null, vm.state.value.person)
        assertEquals(emptyList<TagChip>(), vm.state.value.chips)
        assertEquals(false, vm.state.value.hasFilter)
    }

    @Test
    fun noMatches_isReported_andAnEmptyLibraryHasNoResultsToo() = runBlocking {
        val vm = newViewModel()
        waitFor("the empty library") { vm.state.value.noResults }
        assertEquals(false, vm.state.value.hasFilter)
        lib.track("Alpha", lib.folder())
        instrumentation.runOnMainSync { vm.setText("zzz") }
        waitFor("no match") { vm.state.value.noResults && vm.state.value.hasFilter }
    }

    @Test
    fun startedWithATagValue_showsItAsAFilterChip_andListsOnlyTheTracksWithIt() = runBlocking {
        val f = lib.folder()
        val a = lib.track("Alpha", f)
        lib.track("Bravo", f)
        val c = lib.track("Charlie", f)
        lib.tag(a, "GENRE", "Rock")
        lib.tag(c, "GENRE", "Rock")
        lib.tag(c, "GENRE", "Pop")
        val genre = lib.keyId("GENRE")
        val rock = lib.valueId("Rock")

        // As when a pill on Track details is tapped: the value id and the tag key id.
        val vm = newViewModel(rock, genre)
        waitFor("the filtered list") { vm.state.value.results.map { it.track.title } == listOf("Alpha", "Charlie") && !vm.state.value.loading }
        assertEquals(listOf(TagChip(genre, "GENRE", rock, "Rock")), vm.state.value.chips)
        assertEquals(null, vm.state.value.person)

        instrumentation.runOnMainSync { vm.clearAll() }
        waitFor("everything again") { vm.state.value.results.size == 3 }
    }

    @Test
    fun refresh_picksUpTracksAddedAfterTheListWasLoaded_keepingTheSearchText() = runBlocking {
        val f = lib.folder()
        lib.track("Alpha one", f)
        val vm = newViewModel()
        waitFor("the first list") { vm.state.value.results.size == 1 }

        // A scan adds tracks while the screen is open.
        lib.track("Alpha two", f)
        lib.track("Bravo", f)
        instrumentation.runOnMainSync { vm.refresh() }
        waitFor("all three") { vm.state.value.results.size == 3 && !vm.state.value.loading }

        instrumentation.runOnMainSync { vm.setText("alpha") }
        waitFor("narrowed") { vm.state.value.results.size == 2 && !vm.state.value.loading }
        lib.track("Alpha three", f)
        instrumentation.runOnMainSync { vm.refresh() }
        waitFor("narrowed, with the new one") { vm.state.value.results.size == 3 && !vm.state.value.loading }
        assertEquals("alpha", vm.state.value.text)
    }

    @Test
    fun setSort_listsAgainInThatOrder_andIsKeptForTheNextViewModel() = runBlocking {
        val f = lib.folder()
        lib.track("A", f, year = 2001)
        lib.track("B", f, year = 1990)
        lib.track("C", f)
        val vm = newViewModel()
        waitFor("the first page") { vm.state.value.results.size == 3 }
        assertEquals(listOf("A", "B", "C"), vm.state.value.results.map { it.track.title })

        instrumentation.runOnMainSync { vm.setSort(TrackSort.decode("YEAR:A")) }
        waitFor("the sorted page") { vm.state.value.results.map { it.track.title } == listOf("B", "A", "C") }
        assertEquals(listOf("B", "A", "C"), vm.allTracks().map { it.title })

        instrumentation.runOnMainSync { vm.setSort(TrackSort.decode("YEAR:D")) }
        waitFor("the descending page") { vm.state.value.results.map { it.track.title } == listOf("A", "B", "C") }

        val next = newViewModel()
        assertEquals(TrackSort.decode("YEAR:D"), next.state.value.sort)
        waitFor("the remembered sort") { next.state.value.results.size == 3 }
    }
}
