// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

/**
 * Codec for the multi-value columns of `tracks` (spec/architecture.md §5): values are joined with
 * the unit separator U+001F, never `\0`, which truncates in SQLite and JNI string APIs.
 */
object TagValues {

    const val SEPARATOR = '\u001F'

    /** Text shown between values in list rows, e.g. "A; B". */
    private const val DISPLAY_SEPARATOR = "; "

    fun join(values: List<String>): String = values.joinToString(SEPARATOR.toString())

    /** Inverse of [join]; an empty column holds no values. */
    fun split(joined: String): List<String> =
        if (joined.isEmpty()) emptyList() else joined.split(SEPARATOR)

    fun display(joined: String): String = split(joined).joinToString(DISPLAY_SEPARATOR)
}
