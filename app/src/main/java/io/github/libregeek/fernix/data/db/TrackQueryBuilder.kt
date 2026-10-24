// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

/** Tag filter: tracks carrying [valueId] under tag key [keyId]. */
data class TagCondition(val keyId: Long, val valueId: Long)

/** All parts combine with AND. An empty filter matches every track. */
data class TrackFilter(
    val text: String = "",
    val tags: List<TagCondition> = emptyList(),
    /** `tag_values.id` of a person; matches the person in any role. */
    val personValueId: Long? = null,
    /** Only tracks that have never played through (`lastPlayedAt IS NULL`). */
    val neverPlayed: Boolean = false,
)

/** SQL text plus the arguments to bind to its `?` placeholders. */
data class SqlQuery(val sql: String, val args: List<Any>)

/**
 * Builds the title-sorted, keyset-paged track query of spec/architecture.md §5 "Filtering":
 * each filter is a track-id subquery, the subqueries are INTERSECTed, and the result is applied
 * as `t.id IN (...)`. Values are only ever bound as arguments, never concatenated into the SQL.
 */
object TrackQueryBuilder {

    fun build(
        filter: TrackFilter,
        peopleKeyIds: List<Long>,
        after: TrackCursor = TrackCursor.START,
        limit: Int = PAGE_SIZE,
    ): SqlQuery {
        val subqueries = mutableListOf<String>()
        val subArgs = mutableListOf<Any>()

        FtsQuery.build(filter.text)?.let {
            subqueries += "SELECT rowid FROM tracks_fts WHERE tracks_fts MATCH ?"
            subArgs += it
        }
        for (condition in filter.tags) {
            subqueries += "SELECT trackId FROM track_tags WHERE valueId = ? AND keyId = ?"
            subArgs += condition.valueId
            subArgs += condition.keyId
        }
        filter.personValueId?.let { person ->
            if (peopleKeyIds.isEmpty()) {
                subqueries += "SELECT trackId FROM track_tags WHERE 0"
            } else {
                val marks = peopleKeyIds.joinToString(",") { "?" }
                subqueries += "SELECT trackId FROM track_tags WHERE valueId = ? AND keyId IN ($marks)"
                subArgs += person
                subArgs.addAll(peopleKeyIds)
            }
        }

        val args = mutableListOf<Any>(after.title, after.id)
        val sql = buildString {
            append("SELECT t.id, t.mediaStoreId, t.title, t.album, t.artist, t.durationMs ")
            append("FROM tracks t WHERE (t.title, t.id) > (?, ?)")
            if (filter.neverPlayed) append(" AND t.lastPlayedAt IS NULL")
            if (subqueries.isNotEmpty()) {
                append(" AND t.id IN (")
                append(subqueries.joinToString(" INTERSECT "))
                append(")")
                args.addAll(subArgs)
            }
            append(" ORDER BY t.title, t.id LIMIT ?")
        }
        args += limit
        return SqlQuery(sql, args)
    }
}
