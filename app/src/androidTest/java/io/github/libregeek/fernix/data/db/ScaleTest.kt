// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.tags.PeopleTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that queries stay fast on a huge synthetic library (spec/architecture.md §5, "Scale
 * target"). Skipped unless the `scale` instrumentation argument gives the number of tracks:
 *
 *     ./gradlew connectedAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.scale=1000000 \
 *       -Pandroid.testInstrumentationRunnerArguments.class=io.github.libregeek.fernix.data.db.ScaleTest
 *
 * Timings are written to logcat under the tag FernixScale.
 */
@RunWith(AndroidJUnit4::class)
class ScaleTest {

    private lateinit var db: FernixDatabase
    private lateinit var lib: TestLibrary
    private var tracks = 0
    private val people get() = tracks / 20
    private val genres = 30

    @Before
    fun setUp() {
        tracks = InstrumentationRegistry.getArguments().getString("scale")?.toIntOrNull() ?: 0
        assumeTrue("Pass -Pandroid.testInstrumentationRunnerArguments.scale=<tracks> to run", tracks > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("scale-test.db")
        db = Room.databaseBuilder(context, FernixDatabase::class.java, "scale-test.db").build()
        lib = TestLibrary(db)
    }

    @After
    fun tearDown() {
        if (!::db.isInitialized) return
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("scale-test.db")
    }

    private fun log(message: String) = Log.i("FernixScale", message)

    private inline fun <T> timed(name: String, block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000
        log("$name: $ms ms")
        return result to ms
    }

    private fun exec(sql: String, vararg args: Any) = db.openHelper.writableDatabase.execSQL(sql, args)

    /** Builds the library with set-based SQL: ~3 tag rows per track, one folder. */
    private fun generate() {
        val n = tracks
        val p = people
        val g = genres
        val seq = { upTo: Int -> "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $upTo) " }
        db.openHelper.writableDatabase.beginTransaction()
        try {
            exec("INSERT INTO folders(id, parentId, name, path) VALUES (1, NULL, 'Music', 'vol/Music')")
            exec("INSERT INTO tag_keys(id, `key`) VALUES (1, 'TITLE'), (2, 'ARTIST'), (3, 'GENRE')")

            exec(seq(n) + "INSERT INTO tag_values(id, value) SELECT n, 'Title ' || printf('%07d', n) FROM seq")
            exec(seq(p) + "INSERT INTO tag_values(id, value) SELECT $n + n, 'Person ' || printf('%06d', n) FROM seq")
            exec(seq(g) + "INSERT INTO tag_values(id, value) SELECT $n + $p + n, 'Genre ' || printf('%02d', n) FROM seq")

            // Fires the FTS triggers, like a real scan.
            exec(
                seq(n) + "INSERT INTO tracks(id, mediaStoreId, folderId, displayName, durationMs, dateModified, " +
                    "sizeBytes, title, album, date, artist, composer, lyricist, year, trackNumber, dateAdded) " +
                    "SELECT n, n, 1, 'f' || n || '.mp3', 200000, 0, 1, 'Title ' || printf('%07d', n), " +
                    "'Album ' || (n / 12), '2020', 'Person ' || printf('%06d', n % $p + 1), '', '', 1980 + n % 40, n % 12 + 1, n % 5000 FROM seq",
            )
            exec(
                seq(n) + "INSERT INTO track_tags(trackId, keyId, valueId, ord) " +
                    "SELECT n, 1, n, 0 FROM seq UNION ALL SELECT n, 2, $n + n % $p + 1, 0 FROM seq " +
                    "UNION ALL SELECT n, 3, $n + $p + n % $g + 1, 0 FROM seq",
            )
            exec("INSERT INTO key_values SELECT keyId, valueId, COUNT(*) FROM track_tags GROUP BY keyId, valueId")
            exec("INSERT INTO people SELECT valueId, COUNT(*) FROM track_tags WHERE keyId = 2 GROUP BY valueId")
            db.openHelper.writableDatabase.setTransactionSuccessful()
        } finally {
            db.openHelper.writableDatabase.endTransaction()
        }
    }

    @Test
    fun queriesStayFast() = runBlocking {
        log("Generating $tracks tracks")
        timed("generate") { generate() }
        assertEquals(tracks, db.trackDao().count())
        val search = TrackSearch(db)
        val limitMs = 1000L
        val slow = mutableListOf<String>()
        fun check(name: String, ms: Long) { if (ms > limitMs) slow += "$name took $ms ms" }

        val person = (tracks + 1).toLong()
        val mid = TrackCursor("Title %07d".format(tracks / 2), (tracks / 2).toLong())
        val rock = TagCondition(3, (tracks + people + 1).toLong())

        timed("first page, no filter") { search.page(TrackFilter()) }.let { check("first page", it.second) }
        timed("deep page, no filter") { search.page(TrackFilter(), mid) }.let { check("deep page", it.second) }
        timed("person filter, first page") { search.page(TrackFilter(personValueId = person)) }
            .let { check("person filter", it.second); assertTrue(it.first.isNotEmpty()) }
        timed("genre filter, first page") { search.page(TrackFilter(tags = listOf(rock))) }
            .let { check("genre filter", it.second); assertTrue(it.first.isNotEmpty()) }
        timed("genre + person filter") { search.page(TrackFilter(tags = listOf(rock), personValueId = person)) }
            .let { check("genre + person", it.second) }
        timed("FTS selective: one title") { search.page(TrackFilter(text = "title %07d".format(tracks / 2))) }
            .let { check("FTS selective", it.second); assertEquals(1, it.first.size) }
        timed("FTS selective + person") { search.page(TrackFilter(text = "person 000001")) }
            .let { check("FTS person text", it.second) }
        // Custom sorts (spec §5 "Sorting"): no index serves them, so the whole library is scanned for each page.
        val sorts = mapOf(
            "year, album, track" to TrackSort.YEAR_ALBUM_TRACK,
            "recently added" to TrackSort.RECENTLY_ADDED,
            "year desc, album, track desc" to TrackSort.decode("YEAR:D,ALBUM:A,TRACK:D"),
        )
        for ((name, sort) in sorts) {
            timed("sort $name, first page") { search.page(TrackFilter(), sort = sort) }.let { check("sort $name first", it.second) }
            timed("sort $name, deep page") { search.page(TrackFilter(), TrackCursor("", (tracks / 2 + 2500).toLong()), sort = sort) }
                .let { check("sort $name deep", it.second); assertEquals(PAGE_SIZE, it.first.size) }
        }
        // Broad queries: match a large part of the library. Logged, not asserted.
        timed("FTS broad: 'title'") { search.page(TrackFilter(text = "title")) }
        timed("FTS broad: 't'") { search.page(TrackFilter(text = "t")) }

        timed("people, first page") { db.tagDao().peoplePage("", 0, "%", PAGE_SIZE) }
            .let { check("people first", it.second); assertEquals(PAGE_SIZE, it.first.size) }
        timed("people, deep page") { db.tagDao().peoplePage("Person %06d".format(people / 2), 0, "%", PAGE_SIZE) }
            .let { check("people deep", it.second) }
        timed("genre values, first page") { db.tagDao().valuesPage(3, TagDao.likePrefix(""), "", 0, PAGE_SIZE) }
            .let { check("genre values", it.second); assertEquals(genres, it.first.size) }
        timed("artist values, prefix 'person 0005'") {
            db.tagDao().valuesPage(2, TagDao.likePrefix("person 0005"), "", 0, PAGE_SIZE)
        }.let { check("artist values prefix", it.second) }
        // Huge key (every track has a distinct title): only prefix-narrowed use is realistic.
        timed("title values, prefix 'title 05000'") {
            db.tagDao().valuesPage(1, TagDao.likePrefix("title 05000"), "", 0, PAGE_SIZE)
        }
        timed("title values, no prefix") { db.tagDao().valuesPage(1, TagDao.likePrefix(""), "", 0, PAGE_SIZE) }

        timed("albums, first page") { db.albumDao().page("", -1, "%", PAGE_SIZE) }

        for (filter in listOf(TrackFilter(), TrackFilter(personValueId = person), TrackFilter(text = "title"))) {
            log("PLAN $filter:\n" + lib.plan(TrackQueryBuilder.build(filter, listOf(2L))))
        }
        log("People keys used: ${PeopleTags.KEYS.size}")
        assertTrue("Slow queries: $slow", slow.isEmpty())
    }
}
