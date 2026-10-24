// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

/** Splits a combined tag value such as `Simon; Garfunkel` into its entries (spec/architecture.md §5 *Tag splitting*). */
object TagSplitter {

    val DEFAULT_SEPARATORS = listOf(";", "/", ",")

    /** Tags that can never be split: they hold one thing (spec §5). */
    val UNSPLITTABLE_KEYS = setOf("TITLE", "ALBUM", "DATE")

    /** Whether [value] holds any of the [separators]. */
    fun contains(value: String, separators: List<String>): Boolean =
        separators.any { it.isNotEmpty() && value.contains(it) }

    /**
     * The parts of [value]: split on every separator, each part trimmed, empty parts dropped, and a
     * part repeated (ignoring case) kept once, in its first position. Without a separator in the
     * value the result is the trimmed value itself. Separators are used as given, so ` feat. ` works.
     */
    fun split(value: String, separators: List<String>): List<String> {
        var pieces = listOf(value)
        for (separator in separators.filter { it.isNotEmpty() }) {
            pieces = pieces.flatMap { it.split(separator) }
        }
        return dedupe(pieces.map { it.trim() })
    }

    /** [values] without empties and without case-insensitive repeats, order kept. */
    fun dedupe(values: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (value in values) {
            if (value.isNotEmpty() && result.none { it.equals(value, ignoreCase = true) }) result += value
        }
        return result
    }
}
