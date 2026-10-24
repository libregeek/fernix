// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TestLibrary
import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.ui.nowplaying.TagValue
import io.github.libregeek.fernix.ui.trackdetails.TrackDetailViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The split button of Track details: ask, confirm, and the tags follow by themselves. */
@RunWith(AndroidJUnit4::class)
class TrackDetailViewModelSplitTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var db: FernixDatabase
    private lateinit var lib: TestLibrary
    private lateinit var settings: Settings
    private lateinit var savedDelimiters: List<String>

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, FernixDatabase::class.java).build()
        lib = TestLibrary(db)
        settings = Settings(instrumentation.targetContext)
        savedDelimiters = settings.splitDelimiters
        settings.splitDelimiters = listOf(";")
    }

    @After
    fun close() {
        settings.splitDelimiters = savedDelimiters
        db.close()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @Test
    fun askThenConfirm_splitsTheValueInEveryTrack_andTheTagsFollow() = runBlocking {
        val f = lib.folder()
        val a = lib.track("A", f, artist = listOf("Simon; Garfunkel"))
        val b = lib.track("B", f, artist = listOf("Simon; Garfunkel"))
        lib.tag(a, "ARTIST", "Simon; Garfunkel")
        lib.tag(a, "GENRE", "Rock/Pop")
        lib.tag(b, "ARTIST", "Simon; Garfunkel")
        lib.refreshSummaries(PeopleTags.KEYS) // the library-wide count comes from the summary table
        val valueId = db.tagDao().valueId("Simon; Garfunkel")!!

        lateinit var vm: TrackDetailViewModel
        instrumentation.runOnMainSync { vm = TrackDetailViewModel(a, db, settings) { null } }
        waitFor("tags") { vm.state.value.tags.isNotEmpty() }
        assertEquals(listOf(";"), vm.state.value.delimiters)

        instrumentation.runOnMainSync { vm.askSplit("ARTIST", TagValue(valueId, "Simon; Garfunkel", isPerson = true)) }
        waitFor("the question") { vm.state.value.pendingSplit != null }
        val ask = vm.state.value.pendingSplit!!
        assertEquals(listOf("Simon", "Garfunkel"), ask.parts)
        assertEquals(2, ask.libraryTracks)

        instrumentation.runOnMainSync { vm.confirmSplit() }
        waitFor("the split") { vm.state.value.pendingSplit == null && !vm.state.value.splitting }
        waitFor("the new tags") {
            vm.state.value.tags.single { it.label == "ARTIST" }.values.map { it.text } == listOf("Simon", "Garfunkel")
        }
        // The same value on the other track was split too, and a value Settings doesn't cover is untouched.
        assertEquals(
            listOf("Simon", "Garfunkel"),
            db.tagDao().valuesOfTrackKey(b, db.tagDao().keyId("ARTIST")!!).map { it.value },
        )
        assertEquals("Rock/Pop", vm.state.value.tags.single { it.label == "GENRE" }.values.single().text)
    }

    @Test
    fun cancel_changesNothing_andAValueTheDelimitersDontCover_hasNoQuestion() = runBlocking {
        val f = lib.folder()
        val a = lib.track("A", f)
        lib.tag(a, "ARTIST", "Simon; Garfunkel")
        lib.tag(a, "GENRE", "Rock/Pop")
        lateinit var vm: TrackDetailViewModel
        instrumentation.runOnMainSync { vm = TrackDetailViewModel(a, db, settings) { null } }
        waitFor("tags") { vm.state.value.tags.size == 2 }

        val genre = TagValue(db.tagDao().valueId("Rock/Pop")!!, "Rock/Pop", isPerson = false)
        val artist = TagValue(db.tagDao().valueId("Simon; Garfunkel")!!, "Simon; Garfunkel", isPerson = true)
        instrumentation.runOnMainSync { vm.askSplit("GENRE", genre) }
        Thread.sleep(500)
        assertNull(vm.state.value.pendingSplit) // "/" isn't a delimiter now

        instrumentation.runOnMainSync { vm.askSplit("ARTIST", artist) }
        waitFor("the question") { vm.state.value.pendingSplit != null }
        instrumentation.runOnMainSync { vm.cancelSplit() }
        assertNull(vm.state.value.pendingSplit)
        // Cancelled: the value is still whole in the database.
        assertEquals(listOf("Simon; Garfunkel"), db.tagDao().valuesOfTrackKey(a, db.tagDao().keyId("ARTIST")!!).map { it.value })
    }
}
