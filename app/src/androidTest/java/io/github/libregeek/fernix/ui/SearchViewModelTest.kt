// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TestLibrary
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.ui.search.PersonChip
import io.github.libregeek.fernix.ui.search.SearchViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {

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

        lateinit var vm: SearchViewModel
        instrumentation.runOnMainSync {
            vm = SearchViewModel(db, TrackSearch(db), PlaybackController(instrumentation.targetContext))
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
}
