// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

/** What a Tracks list can be sorted by (spec/architecture.md §5 "Sorting"). */
enum class SortField { TITLE, ALBUM, YEAR, TRACK, DURATION, DATE_ADDED }

/** One level of a sort: a field and its direction. */
data class SortLevel(val field: SortField, val ascending: Boolean = true)

/**
 * Up to [MAX_LEVELS] levels, each field at most once, applied in order; ties end on title and id, so
 * the order is always the same. The default is title ascending, which uses the title index.
 */
data class TrackSort(val levels: List<SortLevel> = listOf(SortLevel(SortField.TITLE))) {

    val isDefault get() = levels == DEFAULT.levels

    /** `YEAR:A,ALBUM:A,TRACK:D`, for the saved state and the settings. */
    fun encode(): String = levels.joinToString(",") { "${it.field.name}:${if (it.ascending) 'A' else 'D'}" }

    companion object {
        const val MAX_LEVELS = 4

        val DEFAULT = TrackSort()

        val YEAR_ALBUM_TRACK = TrackSort(listOf(SortLevel(SortField.YEAR), SortLevel(SortField.ALBUM), SortLevel(SortField.TRACK)))
        val ALBUM_YEAR_TRACK = TrackSort(listOf(SortLevel(SortField.ALBUM), SortLevel(SortField.YEAR), SortLevel(SortField.TRACK)))
        val RECENTLY_ADDED = TrackSort(listOf(SortLevel(SortField.DATE_ADDED, ascending = false)))

        /** Inverse of [encode]; anything unreadable, repeated or beyond the limit is dropped, and nothing left is the default. */
        fun decode(text: String?): TrackSort {
            val levels = text.orEmpty().split(',').mapNotNull { part ->
                val p = part.split(':')
                val field = SortField.entries.firstOrNull { it.name == p.getOrNull(0) } ?: return@mapNotNull null
                SortLevel(field, p.getOrNull(1) != "D")
            }.distinctBy { it.field }.take(MAX_LEVELS)
            return if (levels.isEmpty()) DEFAULT else TrackSort(levels)
        }
    }
}
