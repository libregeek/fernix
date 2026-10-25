// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.libregeek.fernix.tags.PeopleTags

/** A track with the roles the filtered person has on it (empty without a person filter). */
data class TrackResult(val track: TrackRow, val roles: List<String>)

/** Runs filtered, title-sorted, keyset-paged track queries (spec/architecture.md §5 "Filtering"). */
class TrackSearch(private val db: FernixDatabase) {

    private var peopleKeyIds: List<Long>? = null

    /** The people tag key ids can change when a scan adds keys; call after one. */
    fun invalidatePeopleKeys() {
        peopleKeyIds = null
    }

    /** Every track matching [filter] in the order of [sort]: the whole list, to play or queue it. */
    suspend fun all(filter: TrackFilter, sort: TrackSort = TrackSort.DEFAULT): List<TrackRow> {
        val rows = mutableListOf<TrackRow>()
        var cursor = TrackCursor.START
        while (true) {
            val page = page(filter, cursor, sort = sort)
            rows += page.map { it.track }
            if (page.size < PAGE_SIZE) return rows
            cursor = cursor.after(page.last().track)
        }
    }

    /** One page of results after [after] (start with [TrackCursor.START]). */
    suspend fun page(
        filter: TrackFilter,
        after: TrackCursor = TrackCursor.START,
        limit: Int = PAGE_SIZE,
        sort: TrackSort = TrackSort.DEFAULT,
    ): List<TrackResult> {
        val peopleKeys = peopleKeyIds ?: db.tagDao()
            .peopleKeyIds(PeopleTags.KEYS.toList())
            .also { peopleKeyIds = it }

        val built = TrackQueryBuilder.build(filter, peopleKeys, after, limit, sort)
        val rows = db.trackDao().rawPage(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))

        val person = filter.personValueId
        if (person == null || rows.isEmpty() || peopleKeys.isEmpty()) {
            return rows.map { TrackResult(it, emptyList()) }
        }
        val roles = db.trackDao().roles(person, rows.map { it.id }, peopleKeys)
            .groupBy({ it.trackId }, { it.key })
        return rows.map { TrackResult(it, roles[it.id].orEmpty()) }
    }
}
