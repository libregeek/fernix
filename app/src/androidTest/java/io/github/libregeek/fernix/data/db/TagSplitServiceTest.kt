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

    @Test
    fun preview_listsEachCombinedValueOnce_withPartsAndLibraryWideCount() = runBlocking {
        val (a, b, c) = library()
        val artist = lib.keyId("ARTIST")
        val preview = service.preview(listOf(a, b, c), listOf(artist), separators)
        assertEquals(listOf("AC/DC", "Simon; Garfunkel"), preview.map { it.value })
        val simon = preview.single { it.value == "Simon; Garfunkel" }
        assertEquals(listOf("Simon", "Garfunkel"), simon.parts)
        assertEquals(2, simon.libraryTracks)
        assertEquals(listOf("AC", "DC"), preview.single { it.value == "AC/DC" }.parts)
    }

    @Test
    fun preview_onlyLooksAtTheListsTracks_theChosenKeys_andTheChosenSeparators() = runBlocking {
        val (a, _, c, d) = library()
        val artist = lib.keyId("ARTIST")
        assertEquals(emptyList<String>(), service.preview(listOf(d), listOf(artist), separators).map { it.value })
        // GENRE isn't a chosen key; the comma isn't a chosen separator.
        assertEquals(listOf("Simon; Garfunkel"), service.preview(listOf(a), listOf(artist), separators).map { it.value })
        assertEquals(emptyList<String>(), service.preview(listOf(a, c), listOf(artist), listOf(",")).map { it.value })
        assertEquals(emptyList<String>(), service.preview(emptyList(), listOf(artist), separators).map { it.value })
    }

    @Test
    fun preview_worksOnLongTrackLists() = runBlocking {
        val f = lib.folder()
        val ids = (1..1500).map { lib.track("T$it", f).also { id -> lib.tag(id, "GENRE", "Rock/Pop") } }
        val preview = service.preview(ids, listOf(lib.keyId("GENRE")), separators)
        assertEquals(1, preview.size)
    }

    @Test
    fun apply_rewritesTagsInPlace_dedupes_updatesFixedColumn_andLeavesUncheckedValuesWhole() = runBlocking {
        val (a, b, c, d) = library()
        val artist = lib.keyId("ARTIST")
        val simon = service.preview(listOf(a, b), listOf(artist), separators).single { it.value == "Simon; Garfunkel" }

        val progress = mutableListOf<Pair<Int, Int>>()
        service.apply(listOf(simon)) { done, total -> progress += done to total }
        assertEquals(listOf(1 to 1), progress)

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
        val people = db.tagDao().peoplePage("", 0, PAGE_SIZE).associate { it.name to it.trackCount }
        assertEquals(mapOf("AC/DC" to 1, "Ann" to 1, "Garfunkel" to 2, "simon" to 2, "Zed" to 1), people)

        // The rule and the tracks it touched are recorded, so a rescan and an undo can use them.
        val rule = db.splitRuleDao().find(artist, "Simon; Garfunkel")!!
        assertEquals(listOf("Simon", "Garfunkel"), TagValues.split(rule.parts))
        assertEquals(
            setOf(a, b),
            db.openHelper.readableDatabase.query("SELECT trackId FROM split_rule_tracks WHERE ruleId = ${rule.id}")
                .use { c2 -> generateSequence { if (c2.moveToNext()) c2.getLong(0) else null }.toSet() },
        )
    }

    @Test
    fun apply_isLibraryWide_notOnlyOnTheListsTracks() = runBlocking {
        val (a, b) = library()
        val artist = lib.keyId("ARTIST")
        // The preview came from track A only, but track B carries the same value.
        val simon = service.preview(listOf(a), listOf(artist), separators).single()
        service.apply(listOf(simon))
        assertEquals(listOf("Zed", "simon", "Garfunkel"), values(b, "ARTIST"))
    }

    @Test
    fun apply_twice_isHarmless_andAValueStillUsedUnderAnotherKeyStaysInTheDictionary() = runBlocking {
        val f = lib.folder()
        val a = lib.track("A", f)
        lib.tag(a, "ARTIST", "X; Y")
        lib.tag(a, "COMPOSER", "X; Y")
        lib.refreshSummaries(PeopleTags.KEYS)
        val artist = lib.keyId("ARTIST")
        val candidate = service.preview(listOf(a), listOf(artist), separators).single()
        service.apply(listOf(candidate))
        service.apply(listOf(candidate))
        assertEquals(listOf("X", "Y"), values(a, "ARTIST"))
        assertEquals(listOf("X; Y"), values(a, "COMPOSER"))
        assertNotNull(db.tagDao().valueId("X; Y")) // still the composer
        assertEquals(1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM split_rules").use { it.moveToFirst(); it.getInt(0) })
    }
}
