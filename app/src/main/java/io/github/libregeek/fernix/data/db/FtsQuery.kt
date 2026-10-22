// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

/** Builds MATCH expressions for `tracks_fts` from what the user typed. */
object FtsQuery {

    /**
     * Each word becomes a quoted prefix term (`"beat*"`; FTS4 wants the star inside the quotes); terms are ANDed. Quotes and `*` are
     * dropped, so no FTS operator syntax can come from user input. Returns null when nothing
     * searchable was typed. The result is bound as an argument, never concatenated into SQL.
     */
    fun build(text: String): String? {
        val terms = text.split(Regex("\\s+"))
            .map { it.replace("\"", "").replace("*", "") }
            .filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "\"$it*\"" }
    }
}
