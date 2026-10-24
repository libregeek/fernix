// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.tags.PeopleTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {

    private lateinit var db: FernixDatabase
    private lateinit var lib: TestLibrary
    private lateinit var search: TrackSearch

    @Before
    fun open() {
        db = TestLibrary.inMemoryDb(InstrumentationRegistry.getInstrumentation().targetContext)
        lib = TestLibrary(db)
        search = TrackSearch(db)
    }

    @After
    fun close() = db.close()

    private fun titles(results: List<TrackResult>) = results.map { it.track.title }

    // --- schema ---

    @Test
    fun deletingTrack_cascadesToItsRows() = runBlocking {
        val f = lib.folder()
        val t = lib.track("T", f)
        lib.tag(t, "ARTIST", "A")
        val playlist = db.playlistDao().insert(PlaylistEntity(name = "p", createdAt = 0, updatedAt = 0))
        db.playlistDao().insertEntry(PlaylistEntryEntity(playlist, 0, t))
        val rule = db.splitRuleDao().insert(SplitRuleEntity(keyId = lib.keyId("ARTIST"), value = "A;B", parts = "A\u001FB", createdAt = 0))
        db.splitRuleDao().insertRuleTracks(listOf(SplitRuleTrackEntity(rule, t)))

        db.trackDao().deleteById(t)

        fun count(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, count("track_tags"))
        assertEquals(0, count("playlist_entries"))
        assertEquals(0, count("split_rule_tracks"))
        assertEquals(1, count("playlists"))
        assertEquals(1, count("split_rules"))
    }

    @Test
    fun deletingAlbum_clearsTrackAlbumId() = runBlocking {
        val f = lib.folder()
        val a = lib.album("Album", 2000)
        val t = lib.track("T", f, albumId = a)
        db.openHelper.writableDatabase.execSQL("DELETE FROM albums WHERE id = $a")
        assertNull(db.trackDao().byId(t)!!.albumId)
    }

    @Test
    fun deletingFolder_cascadesToChildren_butNotWhenItHoldsTracks() = runBlocking {
        val root = lib.folder("root")
        val child = lib.folder("child", root)
        lib.track("T", child)
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM folders WHERE id = $child")
            fail("A folder with tracks must not be deletable")
        } catch (expected: SQLiteConstraintException) {
        }
        db.trackDao().deleteById(1)
        db.openHelper.writableDatabase.execSQL("DELETE FROM folders WHERE id = $root")
        assertNull(db.folderDao().byPath("vol/child/2"))
    }

    @Test
    fun album_isUniqueByNameAndYear_unknownYearIsZero_ignoringCase() = runBlocking {
        lib.album("Abbey Road", 1969)
        lib.album("Abbey Road", 0)
        lib.album("Abbey Road", 1970)
        for ((name, year) in listOf("Abbey Road" to 0, "abbey road" to 1969)) {
            try {
                lib.album(name, year)
                fail("Duplicate album $name/$year accepted")
            } catch (expected: SQLiteConstraintException) {
            }
        }
        assertNotNull(db.albumDao().find("ABBEY ROAD", 0))
    }

    @Test
    fun tagValues_areSharedAcrossKeys_caseInsensitively() = runBlocking {
        val a = lib.valueId("AC/DC")
        assertEquals(a, lib.valueId("Ac/Dc"))
        assertEquals("AC/DC", db.openHelper.readableDatabase.query("SELECT value FROM tag_values WHERE id = $a").use { it.moveToFirst(); it.getString(0) })
    }

    // --- full-text search ---

    @Test
    fun fts_findsPrefixes_ignoringCaseAndDiacritics() = runBlocking {
        val f = lib.folder()
        lib.track("Halo", f, artist = listOf("Beyoncé"))
        lib.track("Other", f, artist = listOf("Someone"))
        assertEquals(listOf("Halo"), titles(search.page(TrackFilter(text = "beyonce"))))
        assertEquals(listOf("Halo"), titles(search.page(TrackFilter(text = "BEYO"))))
        assertEquals(listOf("Halo"), titles(search.page(TrackFilter(text = "beyoncé"))))
        assertEquals(emptyList<String>(), titles(search.page(TrackFilter(text = "nothing"))))
    }

    @Test
    fun fts_multipleWordsMustAllMatch_acrossColumns() = runBlocking {
        val f = lib.folder()
        lib.track("Yesterday", f, artist = listOf("Beatles"), album = "Help")
        lib.track("Yesterday", f, artist = listOf("Other"), album = "Cover")
        assertEquals(1, search.page(TrackFilter(text = "yest beat")).size)
        assertEquals(2, search.page(TrackFilter(text = "yest")).size)
    }

    @Test
    fun fts_staysInSyncOnUpdateAndDelete() = runBlocking {
        val f = lib.folder()
        val t = lib.track("Old Name", f)
        assertEquals(1, search.page(TrackFilter(text = "old")).size)

        db.openHelper.writableDatabase.execSQL("UPDATE tracks SET title = 'New Name' WHERE id = $t")
        assertEquals(0, search.page(TrackFilter(text = "old")).size)
        assertEquals(1, search.page(TrackFilter(text = "new")).size)

        db.trackDao().deleteById(t)
        assertEquals(0, search.page(TrackFilter(text = "new")).size)
    }

    @Test
    fun fts_userSyntaxIsHarmless() = runBlocking {
        val f = lib.folder()
        lib.track("Plain", f)
        for (text in listOf("\"", "*", "a OR b", "title:", "NEAR/2 (", "-x", "'; DROP TABLE tracks; --")) {
            search.page(TrackFilter(text = text)) // must not throw
        }
        assertEquals(1, db.trackDao().count())
    }

    // --- paging ---

    @Test
    fun pagingWalksEveryTrackOnce_inTitleOrder() = runBlocking {
        val f = lib.folder()
        repeat(450) { lib.track("Track %03d".format(449 - it), f) }

        val seen = mutableListOf<TrackRow>()
        var cursor = TrackCursor.START
        var pages = 0
        while (true) {
            val page = search.page(TrackFilter(), cursor)
            if (page.isEmpty()) break
            pages++
            seen += page.map { it.track }
            cursor = cursor.after(page.last().track)
        }
        assertEquals(3, pages)
        assertEquals(450, seen.size)
        assertEquals((0 until 450).map { "Track %03d".format(it) }, seen.map { it.title })
    }

    @Test
    fun titlesSortIgnoringCase_andDuplicatesAreKeptApart() = runBlocking {
        val f = lib.folder()
        lib.track("banana", f)
        lib.track("Apple", f)
        lib.track("cherry", f)
        lib.track("apple", f)
        assertEquals(listOf("Apple", "apple", "banana", "cherry"), titles(search.page(TrackFilter())))
        // Duplicate titles must not be skipped or repeated across a page boundary.
        val first = search.page(TrackFilter(), TrackCursor.START, limit = 1)
        val second = search.page(TrackFilter(), TrackCursor.START.after(first.single().track), limit = 1)
        assertEquals(listOf("Apple", "apple"), titles(first + second))
    }

    @Test
    fun emptyTitle_isNotSkipped() = runBlocking {
        val f = lib.folder()
        lib.track("", f)
        lib.track("A", f)
        assertEquals(listOf("", "A"), titles(search.page(TrackFilter())))
    }

    @Test
    fun albumPage_ordersByDiscThenTrack_missingNumbersFirst() = runBlocking {
        val f = lib.folder()
        val a = lib.album("Album")
        lib.track("d2t1", f, albumId = a, disc = 2, number = 1)
        lib.track("d1t2", f, albumId = a, disc = 1, number = 2)
        lib.track("none", f, albumId = a)
        lib.track("d1t1", f, albumId = a, disc = 1, number = 1)
        val rows = db.trackDao().albumPage(a, 0, 0, 0, PAGE_SIZE)
        assertEquals(listOf("none", "d1t1", "d1t2", "d2t1"), rows.map { it.title })

        val afterD1T1 = db.trackDao().albumPage(a, 1, 1, rows[1].id, PAGE_SIZE)
        assertEquals(listOf("d1t2", "d2t1"), afterD1T1.map { it.title })
    }

    @Test
    fun folderPage_ordersByFileName() = runBlocking {
        val f = lib.folder()
        val other = lib.folder("other")
        lib.track("x", f, displayName = "b.mp3")
        lib.track("y", f, displayName = "A.mp3")
        lib.track("z", other, displayName = "a.mp3")
        assertEquals(listOf("y", "x"), db.trackDao().folderPage(f, "", 0, PAGE_SIZE).map { it.title })
    }

    @Test
    fun folderChildrenAndAlbumsPage() = runBlocking {
        val root = lib.folder("root")
        listOf("b", "A", "c").forEach { lib.folder(it, root) }
        val names = db.folderDao().childrenPage(root, "", 0, PAGE_SIZE).map { it.name }
        assertEquals(listOf("A", "b", "c"), names)
        assertEquals(listOf("root"), db.folderDao().volumes().map { it.name })

        lib.album("B", 2001); lib.album("B", 0); lib.album("a", 1999)
        val albums = db.albumDao().page("", -1, PAGE_SIZE)
        assertEquals(listOf("a" to 1999, "B" to 0, "B" to 2001), albums.map { it.album to it.year })
        assertEquals(listOf("B" to 2001), db.albumDao().page("B", 0, PAGE_SIZE).map { it.album to it.year })
    }

    // --- filters ---

    private suspend fun filterLibrary(): Map<String, Long> {
        val f = lib.folder()
        val ids = mutableMapOf<String, Long>()
        ids["one"] = lib.track("One", f, artist = listOf("Ann", "Bob")).also {
            lib.tag(it, "ARTIST", "Ann", "Bob"); lib.tag(it, "GENRE", "Rock")
        }
        ids["two"] = lib.track("Two", f, artist = listOf("Bob")).also {
            lib.tag(it, "ARTIST", "Bob"); lib.tag(it, "GENRE", "Jazz"); lib.tag(it, "COMPOSER", "Ann")
        }
        ids["three"] = lib.track("Three", f, artist = listOf("Cy")).also {
            lib.tag(it, "ARTIST", "Cy"); lib.tag(it, "GENRE", "Rock"); lib.tag(it, "CONDUCTOR", "Ann")
        }
        lib.refreshSummaries(PeopleTags.KEYS)
        return ids
    }

    @Test
    fun tagFilter_matchesKeyAndValue() = runBlocking {
        filterLibrary()
        val rock = TagCondition(lib.keyId("GENRE"), lib.valueId("Rock"))
        assertEquals(listOf("One", "Three"), titles(search.page(TrackFilter(tags = listOf(rock)))))
        // Same value under a different key must not match.
        val ann = lib.valueId("Ann")
        val annAsGenre = TagCondition(lib.keyId("GENRE"), ann)
        assertEquals(emptyList<String>(), titles(search.page(TrackFilter(tags = listOf(annAsGenre)))))
    }

    @Test
    fun tagFilters_combineWithAnd() = runBlocking {
        filterLibrary()
        val rock = TagCondition(lib.keyId("GENRE"), lib.valueId("Rock"))
        val bob = TagCondition(lib.keyId("ARTIST"), lib.valueId("Bob"))
        assertEquals(listOf("One"), titles(search.page(TrackFilter(tags = listOf(rock, bob)))))
    }

    @Test
    fun personFilter_matchesAnyRole_andReportsRoles() = runBlocking {
        filterLibrary()
        val results = search.page(TrackFilter(personValueId = lib.valueId("Ann")))
        assertEquals(listOf("One", "Three", "Two"), titles(results))
        val roles = results.associate { it.track.title to it.roles }
        assertEquals(listOf("ARTIST"), roles["One"])
        assertEquals(listOf("COMPOSER"), roles["Two"])
        assertEquals(listOf("CONDUCTOR"), roles["Three"])
    }

    @Test
    fun personFilter_ignoresNonPeopleKeys() = runBlocking {
        val f = lib.folder()
        val t = lib.track("T", f)
        lib.tag(t, "GENRE", "Ann") // same text, but GENRE is not a people key
        lib.refreshSummaries(PeopleTags.KEYS)
        assertEquals(emptyList<String>(), titles(search.page(TrackFilter(personValueId = lib.valueId("Ann")))))
    }

    @Test
    fun personAndTextAndTag_combine() = runBlocking {
        filterLibrary()
        val rock = TagCondition(lib.keyId("GENRE"), lib.valueId("Rock"))
        val filter = TrackFilter(text = "thr", tags = listOf(rock), personValueId = lib.valueId("Ann"))
        assertEquals(listOf("Three"), titles(search.page(filter)))
    }

    @Test
    fun personFilter_withNoPeopleKeysInLibrary_matchesNothing() = runBlocking {
        val f = lib.folder()
        val t = lib.track("T", f)
        lib.tag(t, "GENRE", "X")
        assertEquals(emptyList<String>(), titles(search.page(TrackFilter(personValueId = lib.valueId("X")))))
    }

    @Test
    fun performerWithInstrument_countsAsPeopleKey() = runBlocking {
        val f = lib.folder()
        val t = lib.track("T", f)
        lib.tag(t, "PERFORMER:GUITAR", "Gil")
        lib.refreshSummaries(PeopleTags.KEYS)
        val r = search.page(TrackFilter(personValueId = lib.valueId("Gil")))
        assertEquals(listOf("PERFORMER:GUITAR"), r.single().roles)
        assertEquals(listOf("Gil"), db.tagDao().peoplePage("", 0, PAGE_SIZE).map { it.name })
    }

    // --- pickers and people ---

    @Test
    fun peoplePage_listsEachPersonOnce_byName_withTrackCount() = runBlocking {
        filterLibrary()
        val people = db.tagDao().peoplePage("", 0, PAGE_SIZE)
        assertEquals(listOf("Ann" to 3, "Bob" to 2, "Cy" to 1), people.map { it.name to it.trackCount })
        assertEquals(listOf("Cy"), db.tagDao().peoplePage("Bob", people.first { it.name == "Bob" }.valueId, PAGE_SIZE).map { it.name })
    }

    @Test
    fun roleCounts_countDistinctTracksPerPeopleKey_ignoringOtherKeys() = runBlocking {
        val f = lib.folder()
        val a = lib.track("A", f)
        val b = lib.track("B", f)
        lib.tag(a, "COMPOSER", "Johnson")
        lib.tag(a, "LYRICIST", "Johnson")
        lib.tag(b, "COMPOSER", "Johnson")
        lib.tag(b, "GENRE", "Johnson")
        lib.refreshSummaries(PeopleTags.KEYS)
        val keys = db.tagDao().peopleKeyIds(PeopleTags.KEYS.toList())
        val roles = db.tagDao().roleCounts(lib.valueId("Johnson"), keys)
        assertEquals(listOf("COMPOSER" to 2, "LYRICIST" to 1), roles.map { it.key to it.trackCount })
        assertEquals("Johnson", db.tagDao().valueText(lib.valueId("Johnson")))
    }

    @Test
    fun valuesPage_listsKeyValues_narrowedByTypedPrefix_andPaged() = runBlocking {
        val f = lib.folder()
        listOf("Rock", "Rap", "R_B", "Jazz", "Reggae").forEach { lib.tag(lib.track(it, f), "GENRE", it) }
        lib.tag(lib.track("x", f), "MOOD", "Rock")
        lib.refreshSummaries(PeopleTags.KEYS)
        val genre = lib.keyId("GENRE")
        val dao = db.tagDao()

        val all = dao.valuesPage(genre, TagDao.likePrefix(""), "", 0, PAGE_SIZE)
        assertEquals(listOf("Jazz", "R_B", "Rap", "Reggae", "Rock"), all.map { it.value })

        assertEquals(listOf("Rap", "Reggae", "Rock"), dao.valuesPage(genre, TagDao.likePrefix("r"), "R_B", all.first { it.value == "R_B" }.valueId, PAGE_SIZE).map { it.value })
        assertEquals(listOf("Rap"), dao.valuesPage(genre, TagDao.likePrefix("ra"), "", 0, PAGE_SIZE).map { it.value })
        // "_" and "%" are literal, not wildcards.
        assertEquals(listOf("R_B"), dao.valuesPage(genre, TagDao.likePrefix("r_"), "", 0, PAGE_SIZE).map { it.value })
        assertEquals(emptyList<String>(), dao.valuesPage(genre, TagDao.likePrefix("%"), "", 0, PAGE_SIZE).map { it.value })

        val page1 = dao.valuesPage(genre, TagDao.likePrefix(""), "", 0, 2)
        val page2 = dao.valuesPage(genre, TagDao.likePrefix(""), page1.last().value, page1.last().valueId, 2)
        assertEquals(listOf("Jazz", "R_B", "Rap", "Reggae"), (page1 + page2).map { it.value })
        assertTrue(all.all { it.trackCount == 1 })
    }

    // --- query plans: every filter is served by an index ---

    @Test
    fun plans_useIndexes() {
        val keys = listOf(1L, 2L)
        val unfiltered = lib.plan(TrackQueryBuilder.build(TrackFilter(), keys))
        assertTrue(unfiltered, unfiltered.contains("index_tracks_title"))
        assertFalse(unfiltered, unfiltered.contains("TEMP B-TREE"))

        val tag = lib.plan(TrackQueryBuilder.build(TrackFilter(tags = listOf(TagCondition(1, 2))), keys))
        assertTrue(tag, tag.contains("index_track_tags_valueId_keyId_trackId"))

        val person = lib.plan(TrackQueryBuilder.build(TrackFilter(personValueId = 3), keys))
        assertTrue(person, person.contains("index_track_tags_valueId_keyId_trackId"))

        val text = lib.plan(TrackQueryBuilder.build(TrackFilter(text = "abc"), keys))
        assertTrue(text, text.contains("tracks_fts"))
    }

    // --- folders ---

    private suspend fun folderAt(path: String, parent: Long? = null): Long =
        db.folderDao().insert(FolderEntity(parentId = parent, name = path.substringAfterLast('/'), path = path))

    @Test
    fun subtreeTracks_includesDescendants_notPrefixSiblings_andOrdersByFolderThenName() = runBlocking {
        val vol = folderAt("v")
        val music = folderAt("v/Music", vol)
        val rock = folderAt("v/Music/Rock", music)
        val sibling = folderAt("v/Music2", vol)
        lib.track("b", rock, displayName = "b.mp3")
        lib.track("z", music, displayName = "z.mp3")
        lib.track("A", music, displayName = "A.mp3")
        lib.track("other", sibling)

        val (low, high) = FolderPaths.subtreeRange("v/Music")
        val rows = db.trackDao().subtreeTracks("v/Music", low, high)

        assertEquals(listOf("A", "z", "b"), rows.map { it.title })
        assertEquals(listOf("A", "z"), db.trackDao().folderTracks(music).map { it.title })
    }
}
