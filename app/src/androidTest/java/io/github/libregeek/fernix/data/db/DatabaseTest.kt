// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.ui.art.PersonArtTrack
import kotlinx.coroutines.flow.first
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
    fun playlist_appendKeepsOrderAndSkipsDuplicates_removeLeavesGaps_countsFollow() = runBlocking {
        val f = lib.folder()
        val a = lib.track("A", f)
        val b = lib.track("B", f)
        val c = lib.track("C", f)
        val dao = db.playlistDao()
        val p = dao.insert(PlaylistEntity(name = "Mix", createdAt = 1, updatedAt = 1))
        dao.append(p, listOf(a, b, a), now = 5)
        dao.append(p, listOf(b, c), now = 6)
        assertEquals(listOf("A", "B", "C"), dao.tracks(p).map { it.title })
        assertEquals(6L, dao.byId(p)!!.updatedAt)
        assertEquals(3, dao.summaries().first().single().trackCount)

        assertEquals(listOf("A", "B", "C"), dao.entriesPage(p, -1, PAGE_SIZE).map { it.track.title })
        val bPosition = dao.entriesPage(p, -1, PAGE_SIZE).first { it.track.title == "B" }.position
        dao.removeEntry(p, bPosition)
        assertEquals(listOf("A", "C"), dao.entriesPage(p, -1, PAGE_SIZE).map { it.track.title })
        assertEquals(listOf("C"), dao.entriesPage(p, dao.entriesPage(p, -1, PAGE_SIZE).first().position, PAGE_SIZE).map { it.track.title })
        // A removed track can be added again, after the highest position.
        dao.append(p, listOf(b), now = 7)
        assertEquals(listOf("A", "C", "B"), dao.tracks(p).map { it.title })

        dao.rename(p, "Road", now = 8)
        assertEquals("Road", dao.summaries().first().single().name)
        dao.delete(p)
        assertEquals(emptyList<PlaylistRow>(), dao.summaries().first())
    }

    @Test
    fun albumMediaStoreIds_followDiscAndTrackOrder_andRespectTheLimit() = runBlocking {
        val f = lib.folder()
        val album = lib.album("Abbey", 1969)
        val t2 = lib.track("Two", f, albumId = album, disc = 1, number = 2)
        val t1 = lib.track("One", f, albumId = album, disc = 1, number = 1)
        val d2 = lib.track("DiscTwo", f, albumId = album, disc = 2, number = 1)
        lib.track("Other", f, albumId = lib.album("Elsewhere"))
        fun mediaStoreId(trackId: Long) = runBlocking { db.trackDao().byId(trackId)!!.mediaStoreId }
        assertEquals(listOf(t1, t2, d2).map(::mediaStoreId), db.trackDao().albumMediaStoreIds(album, 20))
        assertEquals(listOf(t1, t2).map(::mediaStoreId), db.trackDao().albumMediaStoreIds(album, 2))
    }

    // --- Now Playing tags ---

    @Test
    fun playingTags_readOnlyTheSlotKeysOfThatTrack_byKeyThenOrd_andFollowChanges() = runBlocking {
        val f = lib.folder()
        val a = lib.track("A", f)
        val b = lib.track("B", f)
        lib.tag(a, "COMPOSER", "Johnson")
        lib.tag(a, "ARTIST", "Zed", "Amy")
        lib.tag(a, "GENRE", "Rock")
        lib.tag(a, "PERFORMER:GUITAR", "Gil")
        lib.tag(b, "COMPOSER", "Other")

        val tags = db.tagDao()
        val keys = tags.slotKeyIds(listOf("COMPOSER", "ARTIST"), includePerformer = false)
        assertEquals(2, keys.size)
        assertEquals(
            listOf("ARTIST" to "Zed", "ARTIST" to "Amy", "COMPOSER" to "Johnson"),
            tags.playingTags(a, keys).first().map { it.key to it.value },
        )
        // The PERFORMER slot stands for every PERFORMER:<instrument> key.
        val withPerformer = tags.slotKeyIds(listOf("PERFORMER"), includePerformer = true)
        assertEquals(listOf("PERFORMER:GUITAR" to "Gil"), tags.playingTags(a, withPerformer).first().map { it.key to it.value })
        assertEquals(emptyList<Long>(), tags.slotKeyIds(listOf("PERFORMER"), includePerformer = false))

        // The track row is observable too, and goes null when deleted.
        assertEquals("A", db.trackDao().observe(a).first()!!.title)
        db.trackDao().deleteById(a)
        assertEquals(null, db.trackDao().observe(a).first())
    }

    @Test
    fun allTagsOf_listsEveryKeyWithAllValues_ofThatTrackOnly_andFolderById() = runBlocking {
        val f = lib.folder("Rock")
        val a = lib.track("A", f)
        val b = lib.track("B", f)
        lib.tag(a, "GENRE", "Rock")
        lib.tag(a, "ARTIST", "Zed", "Amy")
        lib.tag(b, "BPM", "99")
        assertEquals(
            listOf("ARTIST" to "Zed", "ARTIST" to "Amy", "GENRE" to "Rock"),
            db.tagDao().allTagsOf(a).first().map { it.key to it.value },
        )
        assertEquals("Rock", db.folderDao().byId(f)!!.name)
        assertEquals(null, db.folderDao().byId(9999))
    }

    @Test
    fun personArtDb_givesTheFirst20TracksById_withRoleValues_andTheTopRole() = runBlocking {
        val f = lib.folder()
        val ids = (1..25).map { lib.track("T$it", f) }
        ids.forEach { lib.tag(it, "COMPOSER", "Johnson") }
        lib.tag(ids[0], "ARTIST", "Ann", "Bob")
        lib.tag(ids[0], "GENRE", "Rock")
        lib.tag(ids[1], "ARTIST", "Johnson")
        lib.refreshSummaries(PeopleTags.KEYS)
        val johnson = lib.valueId("Johnson")
        val source = PersonArtDb(db)

        val tracks = source.tracks(johnson, limit = 20)
        assertEquals(20, tracks.size)
        assertEquals(ids.take(20).map { runBlocking { db.trackDao().byId(it)!!.mediaStoreId } }, tracks.map { it.mediaStoreId })
        assertEquals(mapOf("COMPOSER" to listOf("Johnson"), "ARTIST" to listOf("Ann", "Bob")), tracks[0].valuesByKey)
        assertEquals(setOf("COMPOSER", "ARTIST"), tracks[1].valuesByKey.keys)
        assertEquals("COMPOSER", source.topRoleKey(johnson)) // 25 composer tracks vs 1 artist track
        assertEquals(emptyList<PersonArtTrack>(), source.tracks(lib.valueId("Rock"), 20))
    }

    // --- default playlists and play history ---

    @Test
    fun recentlyAdded_isTheNewest50_byDateAddedThenId() = runBlocking {
        val f = lib.folder()
        val ids = (1..55).map { lib.track("T$it", f, dateAdded = it.toLong()) }
        val tied1 = lib.track("tied1", f, dateAdded = 100)
        val tied2 = lib.track("tied2", f, dateAdded = 100)
        val rows = db.trackDao().recentlyAdded()
        assertEquals(DEFAULT_PLAYLIST_SIZE, rows.size)
        assertEquals(listOf(tied2, tied1, ids[54], ids[53]), rows.take(4).map { it.id })
        // 57 tracks: the two ties plus T55 down to T8 make the 50.
        assertEquals("T8", rows.last().title)
        assertTrue(rows.none { it.title == "T7" })
    }

    @Test
    fun neverPlayed_isTitleSortedAndShrinksAsTracksArePlayed() = runBlocking {
        val f = lib.folder()
        lib.track("B", f)
        val a = lib.track("A", f)
        lib.track("C", f, playCount = 1, lastPlayedAt = 5)
        assertEquals(listOf("A", "B"), titles(search.page(TrackFilter(neverPlayed = true))))
        db.trackDao().recordPlay(a, now = 9)
        assertEquals(listOf("B"), titles(search.page(TrackFilter(neverPlayed = true))))
        // All Songs is simply the empty filter.
        assertEquals(listOf("A", "B", "C"), titles(search.page(TrackFilter())))
    }

    @Test
    fun mostPlayed_ordersByCountThenLatestPlay_excludesUnplayed_andRecordPlayCounts() = runBlocking {
        val f = lib.folder()
        lib.track("never", f)
        val low = lib.track("low", f, playCount = 1, lastPlayedAt = 50)
        val oldTie = lib.track("oldTie", f, playCount = 3, lastPlayedAt = 10)
        val newTie = lib.track("newTie", f, playCount = 3, lastPlayedAt = 20)
        assertEquals(listOf(newTie, oldTie, low), db.trackDao().mostPlayed().map { it.id })

        db.trackDao().recordPlay(low, now = 99)
        db.trackDao().recordPlay(low, now = 100)
        db.trackDao().recordPlay(low, now = 101)
        val after = db.trackDao().byId(low)!!
        assertEquals(4, after.playCount)
        assertEquals(101L, after.lastPlayedAt)
        assertEquals(low, db.trackDao().mostPlayed().first().id)
    }

    @Test
    fun mostPlayed_isCappedAt50() = runBlocking {
        val f = lib.folder()
        repeat(60) { lib.track("T$it", f, playCount = it + 1, lastPlayedAt = 1) }
        val rows = db.trackDao().mostPlayed()
        assertEquals(DEFAULT_PLAYLIST_SIZE, rows.size)
        assertEquals("T59", rows.first().title)
    }

    @Test
    fun favourites_toggleAddsOnceAndRemoves_flowFollows_deletedTrackLeaves() = runBlocking {
        val f = lib.folder()
        val a = lib.track("A", f)
        val b = lib.track("B", f)
        val dao = db.playlistDao()
        // The in-memory test database has no creation callback, so create Favourites like it does.
        dao.insert(PlaylistEntity(name = FernixDatabase.FAVOURITES_NAME, kind = PlaylistEntity.KIND_FAVOURITES, createdAt = 1, updatedAt = 1))
        val favourites = dao.favouritesId()!!

        assertEquals(false, dao.isFavourite(a).first())
        assertEquals(true, dao.toggleFavourite(a, now = 5))
        assertEquals(true, dao.isFavourite(a).first())
        assertEquals(false, dao.isFavourite(b).first())
        assertEquals(listOf("A"), dao.tracks(favourites).map { it.title })

        // Adding the same track again from "Add to playlist" is a no-op.
        dao.append(favourites, listOf(a), now = 6)
        assertEquals(1, dao.tracks(favourites).size)

        assertEquals(false, dao.toggleFavourite(a, now = 7))
        assertEquals(false, dao.isFavourite(a).first())
        assertEquals(emptyList<String>(), dao.tracks(favourites).map { it.title })

        dao.toggleFavourite(b, now = 8)
        db.trackDao().deleteById(b)
        assertEquals(emptyList<String>(), dao.tracks(favourites).map { it.title })
        // Favourites never shows among the user's playlists.
        assertEquals(emptyList<PlaylistRow>(), dao.summaries().first())
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
