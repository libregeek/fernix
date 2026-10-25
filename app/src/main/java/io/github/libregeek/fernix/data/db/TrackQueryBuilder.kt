// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

/** Tag filter: tracks carrying [valueId] under tag key [keyId]. Conditions on the same key are ORed. */
data class TagCondition(val keyId: Long, val valueId: Long)

/**
 * The text, the person and the tag keys combine with AND; several tag values of one key are ORed.
 * An empty filter matches every track.
 */
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
        sort: TrackSort = TrackSort.DEFAULT,
    ): SqlQuery {
        val subqueries = mutableListOf<String>()
        val subArgs = mutableListOf<Any>()

        FtsQuery.build(filter.text)?.let {
            subqueries += "SELECT rowid FROM tracks_fts WHERE tracks_fts MATCH ?"
            subArgs += it
        }
        // One subquery per tag key: several values of the same key are an OR inside it, and the
        // subqueries of different keys are INTERSECTed (an AND), as is everything else.
        for ((keyId, conditions) in filter.tags.groupBy { it.keyId }) {
            val valueIds = conditions.map { it.valueId }.distinct()
            subqueries += "SELECT trackId FROM track_tags WHERE keyId = ? AND valueId IN (${valueIds.joinToString(",") { "?" }})"
            subArgs += keyId
            subArgs.addAll(valueIds)
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

        return if (sort.isDefault) byTitle(filter, subqueries, subArgs, after, limit) else bySort(filter, subqueries, subArgs, after, limit, sort)
    }

    private fun byTitle(filter: TrackFilter, subqueries: List<String>, subArgs: List<Any>, after: TrackCursor, limit: Int): SqlQuery {
        val args = mutableListOf<Any>(after.title, after.id)
        val sql = buildString {
            append("SELECT t.id, t.mediaStoreId, t.title, t.album, t.artist, t.durationMs, t.albumId, t.date, t.folderId ")
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

    /** One column of the sort: a SQL expression over the alias `@`, its direction, and whether it is text (compared without case). */
    private class Term(val expr: String, val ascending: Boolean, val text: Boolean = false)

    /**
     * The sort as a flat list of terms. A field that can be unknown (album, year, track) first gets a
     * 0/1 "unknown" term that is always ascending, so unknown values come last in either direction.
     * Title and id close the list, so ties are always broken the same way.
     */
    private fun terms(sort: TrackSort): List<Term> {
        val out = mutableListOf<Term>()
        for ((field, asc) in sort.levels) {
            when (field) {
                SortField.TITLE -> out += Term("@.title", asc, text = true)
                SortField.ALBUM -> {
                    out += Term("(@.album = '')", true)
                    out += Term("@.album", asc, text = true)
                }
                SortField.YEAR -> {
                    out += Term("(@.year IS NULL)", true)
                    out += Term("COALESCE(@.year, 0)", asc)
                }
                SortField.TRACK -> {
                    out += Term("(@.trackNumber IS NULL)", true)
                    out += Term("COALESCE(@.discNumber, 0)", asc)
                    out += Term("COALESCE(@.trackNumber, 0)", asc)
                }
                SortField.DURATION -> out += Term("@.durationMs", asc)
                SortField.DATE_ADDED -> out += Term("@.dateAdded", asc)
            }
        }
        if (sort.levels.none { it.field == SortField.TITLE }) out += Term("@.title", true, text = true)
        out += Term("@.id", true)
        return out
    }

    /**
     * A custom sort. The page starts after the row [after] names by its id: that row's sort values are
     * read once in a CTE (`ref`), and the "comes after" test is the usual keyset chain, one OR branch per
     * term, which works for any mix of directions (a row-value comparison only for one direction).
     */
    private fun bySort(filter: TrackFilter, subqueries: List<String>, subArgs: List<Any>, after: TrackCursor, limit: Int, sort: TrackSort): SqlQuery {
        val terms = terms(sort)
        val args = mutableListOf<Any>()
        val sql = buildString {
            val cursor = after.id != 0L
            if (cursor) {
                append("WITH ref AS (SELECT ")
                append(terms.mapIndexed { i, term -> "${term.expr.replace("@.", "r.")} AS v$i" }.joinToString(", "))
                append(" FROM tracks r WHERE r.id = ?) ")
                args += after.id
            }
            append("SELECT t.id, t.mediaStoreId, t.title, t.album, t.artist, t.durationMs, t.albumId, t.date, t.folderId FROM tracks t")
            if (cursor) append(", ref")
            append(" WHERE 1")
            if (cursor) {
                fun cmp(i: Int, op: String) =
                    "${terms[i].expr.replace("@.", "t.")}${if (terms[i].text) " COLLATE NOCASE" else ""} $op ref.v$i"
                val branches = terms.indices.joinToString(" OR ") { k ->
                    val equal = (0 until k).map { cmp(it, "=") }
                    (equal + cmp(k, if (terms[k].ascending) ">" else "<")).joinToString(" AND ", "(", ")")
                }
                append(" AND ($branches)")
            }
            if (filter.neverPlayed) append(" AND t.lastPlayedAt IS NULL")
            if (subqueries.isNotEmpty()) {
                append(" AND t.id IN (")
                append(subqueries.joinToString(" INTERSECT "))
                append(")")
                args.addAll(subArgs)
            }
            append(" ORDER BY ")
            append(terms.joinToString(", ") { "${it.expr.replace("@.", "t.")}${if (it.text) " COLLATE NOCASE" else ""} ${if (it.ascending) "ASC" else "DESC"}" })
            append(" LIMIT ?")
        }
        args += limit
        return SqlQuery(sql, args)
    }
}
