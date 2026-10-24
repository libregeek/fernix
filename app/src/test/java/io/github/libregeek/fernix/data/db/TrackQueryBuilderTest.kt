// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackQueryBuilderTest {

    private val tail = " ORDER BY t.title, t.id LIMIT ?"

    @Test
    fun emptyFilter_pagesAllTracks() {
        val q = TrackQueryBuilder.build(TrackFilter(), emptyList())
        assertEquals(
            "SELECT t.id, t.mediaStoreId, t.title, t.album, t.artist, t.durationMs " +
                "FROM tracks t WHERE (t.title, t.id) > (?, ?)$tail",
            q.sql,
        )
        assertEquals(listOf<Any>("", 0L, PAGE_SIZE), q.args)
    }

    @Test
    fun neverPlayed_addsAFixedCondition_withoutArguments() {
        val q = TrackQueryBuilder.build(TrackFilter(neverPlayed = true), emptyList())
        assertEquals(
            "SELECT t.id, t.mediaStoreId, t.title, t.album, t.artist, t.durationMs " +
                "FROM tracks t WHERE (t.title, t.id) > (?, ?) AND t.lastPlayedAt IS NULL$tail",
            q.sql,
        )
        assertEquals(listOf<Any>("", 0L, PAGE_SIZE), q.args)
    }

    @Test
    fun cursorAndLimit_areBound() {
        val q = TrackQueryBuilder.build(TrackFilter(), emptyList(), TrackCursor("Abbey", 7), 50)
        assertEquals(listOf<Any>("Abbey", 7L, 50), q.args)
    }

    @Test
    fun tagFilter_usesTrackTagsIndexSide() {
        val q = TrackQueryBuilder.build(TrackFilter(tags = listOf(TagCondition(keyId = 3, valueId = 9))), emptyList())
        assertTrue(q.sql.contains("t.id IN (SELECT trackId FROM track_tags WHERE valueId = ? AND keyId = ?)"))
        assertEquals(listOf<Any>("", 0L, 9L, 3L, PAGE_SIZE), q.args)
    }

    @Test
    fun severalFilters_areIntersected_andArgsFollowPlaceholderOrder() {
        val filter = TrackFilter(
            text = "beat",
            tags = listOf(TagCondition(1, 10), TagCondition(2, 20)),
            personValueId = 99,
        )
        val q = TrackQueryBuilder.build(filter, listOf(5L, 6L), TrackCursor("x", 1), 10)
        assertEquals(
            "SELECT t.id, t.mediaStoreId, t.title, t.album, t.artist, t.durationMs FROM tracks t " +
                "WHERE (t.title, t.id) > (?, ?) AND t.id IN (" +
                "SELECT rowid FROM tracks_fts WHERE tracks_fts MATCH ? INTERSECT " +
                "SELECT trackId FROM track_tags WHERE valueId = ? AND keyId = ? INTERSECT " +
                "SELECT trackId FROM track_tags WHERE valueId = ? AND keyId = ? INTERSECT " +
                "SELECT trackId FROM track_tags WHERE valueId = ? AND keyId IN (?,?))$tail",
            q.sql,
        )
        assertEquals(listOf<Any>("x", 1L, "\"beat*\"", 10L, 1L, 20L, 2L, 99L, 5L, 6L, 10), q.args)
        assertEquals(q.sql.count { it == '?' }, q.args.size)
    }

    @Test
    fun userInput_isNeverInTheSql() {
        val evil = "'; DROP TABLE tracks; --"
        val q = TrackQueryBuilder.build(TrackFilter(text = evil), emptyList(), TrackCursor(evil, 1))
        assertFalse(q.sql.contains("DROP"))
        assertFalse(q.sql.contains(evil))
        assertTrue(q.args.contains(evil))
    }

    @Test
    fun personFilter_withoutPeopleKeys_matchesNothing() {
        val q = TrackQueryBuilder.build(TrackFilter(personValueId = 4), emptyList())
        assertTrue(q.sql.contains("SELECT trackId FROM track_tags WHERE 0"))
        assertEquals(q.sql.count { it == '?' }, q.args.size)
    }

    @Test
    fun blankText_addsNoFtsSubquery() {
        val q = TrackQueryBuilder.build(TrackFilter(text = "  "), emptyList())
        assertFalse(q.sql.contains("tracks_fts"))
    }
}
