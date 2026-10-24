// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.tags.TagValues
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagSplitServiceTest {

    private lateinit var db: FernixDatabase
    private lateinit var lib: TestLibrary
    private lateinit var service: TagSplitService

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FernixDatabase::class.java).build()
        lib = TestLibrary(db)
        service = TagSplitService(db)
    }

    @After
    fun close() = db.close()

    private val separators = listOf(";", "/")

    private suspend fun values(trackId: Long, key: String): List<String> =
        db.tagDao().valuesOfTrackKey(trackId, lib.keyId(key)).map { it.value }

    /** Two tracks with "Simon; Garfunkel" (one with a second artist), one with AC/DC, one untouched. */
    private suspend fun library(): List<Long> {
        val f = lib.folder()
        val a = lib.track("A", f, artist = listOf("Simon; Garfunkel"))
        val b = lib.track("B", f, artist = listOf("Zed", "Simon; Garfunkel", "simon"))
        val c = lib.track("C", f, artist = listOf("AC/DC"))
        val d = lib.track("D", f, artist = listOf("Ann"))
        lib.tag(a, "ARTIST", "Simon; Garfunkel")
        lib.tag(b, "ARTIST", "Zed", "Simon; Garfunkel", "simon")
        lib.tag(c, "ARTIST", "AC/DC")
        lib.tag(d, "ARTIST", "Ann")
        lib.tag(a, "GENRE", "Rock/Pop")
        lib.refreshSummaries(PeopleTags.KEYS)
        return listOf(a, b, c, d)
    }

    private suspend fun candidate(trackId: Long, key: String, text: String, delimiters: List<String> = separators): SplitCandidate? {
        val ref = db.tagDao().valuesOfTrackKey(trackId, lib.keyId(key)).single { it.value == text }
        return service.candidateFor(key, ref.valueId, ref.value, delimiters)
    }

    @Test
    fun candidateFor_givesThePartsAndTheLibraryWideCount() = runBlocking {
        val (a, _, c) = library()
        val simon = candidate(a, "ARTIST", "Simon; Garfunkel")!!
        assertEquals(listOf("Simon", "Garfunkel"), simon.parts)
        assertEquals(2, simon.libraryTracks) // tracks A and B carry it
        assertEquals(listOf("AC", "DC"), candidate(c, "ARTIST", "AC/DC")!!.parts)
    }

    @Test
    fun candidateFor_isNullWhenTheDelimitersLeaveTheValueAlone_orTheTagCantBeSplit() = runBlocking {
        val (a, _, c, d) = library()
        assertNull(candidate(d, "ARTIST", "Ann")) // no delimiter in it
        assertNull(candidate(a, "ARTIST", "Simon; Garfunkel", delimiters = listOf(","))) // not one of the chosen delimiters
        assertNull(candidate(a, "ARTIST", "Simon; Garfunkel", delimiters = emptyList()))
        // AC/DC stays whole as long as "/" isn't a delimiter.
        assertNull(candidate(c, "ARTIST", "AC/DC", delimiters = listOf(";")))
        lib.tag(d, "TITLE", "A; B")
        assertNull(candidate(d, "TITLE", "A; B")) // TITLE, ALBUM and DATE are never split
        assertNull(service.candidateFor("NO_SUCH_KEY", 1, "A; B", separators))
    }

    @Test
    fun split_rewritesTagsInPlace_dedupes_updatesFixedColumn_andLeavesUncheckedValuesWhole() = runBlocking {
        val (a, b, c, d) = library()
        val artist = lib.keyId("ARTIST")
        val simon = candidate(a, "ARTIST", "Simon; Garfunkel")!!

        service.split(simon)

        // The dictionary keeps the first spelling of a value (spec §5): B's lowercase "simon" came first.
        assertEquals(listOf("simon", "Garfunkel"), values(a, "ARTIST"))
        // B: Zed, [Simon, Garfunkel], simon -> the repeated "simon" is dropped, order kept.
        assertEquals(listOf("Zed", "simon", "Garfunkel"), values(b, "ARTIST"))
        assertEquals(listOf("AC/DC"), values(c, "ARTIST")) // not checked: stays whole
        assertEquals(listOf("Ann"), values(d, "ARTIST"))

        // The fixed column follows, in the same order, with the parts as the rule spells them.
        assertEquals(listOf("Zed", "Simon", "Garfunkel"), TagValues.split(db.trackDao().byId(b)!!.artist))
        // FTS sees the new text: both tracks now match "garfunkel".
        assertEquals(
            2,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tracks_fts WHERE tracks_fts MATCH 'garfunkel*'")
                .use { it.moveToFirst(); it.getInt(0) },
        )

        // The joined value is gone from the dictionary and the summaries; the parts are counted.
        assertNull(db.tagDao().valueId("Simon; Garfunkel"))
        assertNull(db.tagDao().libraryTrackCount(artist, simon.valueId))
        assertEquals(2, db.tagDao().libraryTrackCount(artist, db.tagDao().valueId("Simon")!!))
        assertEquals(2, db.tagDao().libraryTrackCount(artist, db.tagDao().valueId("Garfunkel")!!))
        val people = db.tagDao().peoplePage("", 0, TagDao.likeContains(""), PAGE_SIZE).associate { it.name to it.trackCount }
        assertEquals(mapOf("AC/DC" to 1, "Ann" to 1, "Garfunkel" to 2, "simon" to 2, "Zed" to 1), people)
    }

    @Test
    fun split_isLibraryWide_notOnlyOnTheListsTracks() = runBlocking {
        val (a, b) = library()
        val artist = lib.keyId("ARTIST")
        // The split is asked for on track A only, but track B carries the same value.
        val simon = candidate(a, "ARTIST", "Simon; Garfunkel")!!
        service.split(simon)
        assertEquals(listOf("Zed", "simon", "Garfunkel"), values(b, "ARTIST"))
    }

    @Test
    fun split_twice_isHarmless_andAValueStillUsedUnderAnotherKeyStaysInTheDictionary() = runBlocking {
        val f = lib.folder()
        val a = lib.track("A", f)
        lib.tag(a, "ARTIST", "X; Y")
        lib.tag(a, "COMPOSER", "X; Y")
        lib.refreshSummaries(PeopleTags.KEYS)
        val artist = lib.keyId("ARTIST")
        val candidate = candidate(a, "ARTIST", "X; Y")!!
        service.split(candidate)
        service.split(candidate)
        assertEquals(listOf("X", "Y"), values(a, "ARTIST"))
        assertEquals(listOf("X; Y"), values(a, "COMPOSER"))
        assertNotNull(db.tagDao().valueId("X; Y")) // still the composer
    }
}
