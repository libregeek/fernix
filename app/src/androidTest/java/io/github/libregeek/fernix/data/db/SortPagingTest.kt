// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Every sort, paged with a small page, lists each track once and in the order an independent
 * comparator gives (spec/architecture.md §5 "Sorting"): mixed directions, unknown values last,
 * ties broken by title then id.
 */
@RunWith(AndroidJUnit4::class)
class SortPagingTest {

    private lateinit var db: FernixDatabase
    private lateinit var search: TrackSearch
    private val tracks = mutableListOf<TrackEntity>()

    @Before
    fun setUp() = runBlocking {
        db = TestLibrary.inMemoryDb(InstrumentationRegistry.getInstrumentation().targetContext)
        search = TrackSearch(db)
        val lib = TestLibrary(db)
        val folder = lib.folder()
        val random = Random(42)
        val albums = listOf("", "Abbey Road", "abbey road", "Blue", "Zebra", "Álbum".replace("Á", "A"))
        repeat(60) { i ->
            // Few distinct values, so every level has many ties and the next level matters.
            lib.track(
                title = listOf("Alpha", "beta", "Gamma", "delta", "Echo")[random.nextInt(5)],
                folderId = folder,
                album = albums[random.nextInt(albums.size)],
                year = listOf(null, 1990, 1995, 2001)[random.nextInt(4)],
                disc = listOf(null, 1, 2)[random.nextInt(3)],
                number = listOf(null, 1, 2, 3, 4)[random.nextInt(5)],
                durationMs = (random.nextInt(4) * 1000).toLong(),
                dateAdded = random.nextInt(4).toLong(),
            )
        }
        tracks += db.openHelper.readableDatabase.query("SELECT id FROM tracks").use { c ->
            buildList { while (c.moveToNext()) add(db.trackDao().byId(c.getLong(0))!!) }
        }
    }

    @After
    fun tearDown() = db.close()

    private fun compareLevel(a: TrackEntity, b: TrackEntity, level: SortLevel): Int {
        val dir = if (level.ascending) 1 else -1
        fun <T : Comparable<T>> unknownLast(x: T?, y: T?): Int = when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            else -> x.compareTo(y) * dir
        }
        return when (level.field) {
            SortField.TITLE -> a.title.lowercase().compareTo(b.title.lowercase()) * dir
            SortField.ALBUM -> unknownLast(a.album.lowercase().takeIf { it.isNotEmpty() }, b.album.lowercase().takeIf { it.isNotEmpty() })
            SortField.YEAR -> unknownLast(a.year, b.year)
            SortField.TRACK -> {
                // No track number comes last (still by disc, then); otherwise disc, then number.
                val unknown = (a.trackNumber == null).compareTo(b.trackNumber == null)
                if (unknown != 0) unknown else {
                    val byDisc = (a.discNumber ?: 0).compareTo(b.discNumber ?: 0) * dir
                    if (byDisc != 0) byDisc else (a.trackNumber ?: 0).compareTo(b.trackNumber ?: 0) * dir
                }
            }
            SortField.DURATION -> a.durationMs.compareTo(b.durationMs) * dir
            SortField.DATE_ADDED -> a.dateAdded.compareTo(b.dateAdded) * dir
        }
    }

    private fun expected(sort: TrackSort): List<Long> {
        val comparator = Comparator<TrackEntity> { a, b ->
            for (level in sort.levels) compareLevel(a, b, level).let { if (it != 0) return@Comparator it }
            val byTitle = a.title.lowercase().compareTo(b.title.lowercase())
            if (byTitle != 0) byTitle else a.id.compareTo(b.id)
        }
        return tracks.sortedWith(comparator).map { it.id }
    }

    private fun sorts() = listOf(
        TrackSort.YEAR_ALBUM_TRACK,
        TrackSort.ALBUM_YEAR_TRACK,
        TrackSort.RECENTLY_ADDED,
        TrackSort(listOf(SortLevel(SortField.YEAR, false), SortLevel(SortField.ALBUM, true), SortLevel(SortField.TRACK, false))),
        TrackSort(listOf(SortLevel(SortField.ALBUM, false), SortLevel(SortField.YEAR, false), SortLevel(SortField.TRACK, true))),
        TrackSort(listOf(SortLevel(SortField.DURATION, false), SortLevel(SortField.DATE_ADDED, true), SortLevel(SortField.TITLE, false), SortLevel(SortField.YEAR, true))),
        TrackSort(listOf(SortLevel(SortField.TITLE, false))),
        TrackSort(listOf(SortLevel(SortField.TRACK, true))),
    )

    @Test
    fun everySort_pagedInSmallPages_isCompleteAndInOrder() = runBlocking {
        for (sort in sorts()) {
            val got = mutableListOf<Long>()
            var cursor = TrackCursor.START
            while (true) {
                val page = search.page(TrackFilter(), cursor, limit = 7, sort = sort)
                got += page.map { it.track.id }
                if (page.size < 7) break
                cursor = cursor.after(page.last().track)
            }
            assertEquals("sort ${sort.encode()}", expected(sort), got)
        }
    }

    @Test
    fun all_followsTheSort() = runBlocking {
        val sort = TrackSort.YEAR_ALBUM_TRACK
        assertEquals(expected(sort), search.all(TrackFilter(), sort).map { it.id })
    }

    @Test
    fun theDefaultSort_isStillByTitle() = runBlocking {
        val got = search.all(TrackFilter()).map { it.id }
        assertEquals(tracks.sortedWith(compareBy({ it.title.lowercase() }, { it.id })).map { it.id }, got)
    }
}
