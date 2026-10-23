// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

/**
 * The fixed `tracks` columns of spec/architecture.md §5, derived from a file's tags. The six
 * multi-value fields are `U+001F`-joined ([TagValues]); an absent tag is the empty string.
 */
data class TrackFields(
    val title: String,
    val album: String,
    val date: String,
    val artist: String,
    val composer: String,
    val lyricist: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
) {
    /**
     * The album this track belongs to, as the `(album, year)` key of `albums`: the first `ALBUM`
     * value, with year 0 for unknown. Null when the track has no album.
     */
    val albumKey: Pair<String, Int>?
        get() = TagValues.split(album).firstOrNull()?.let { it to (year ?: 0) }

    companion object {
        /** [tags] must already be through [TagNormalizer]; split rules (§5) are applied by the caller. */
        fun from(tags: Map<String, List<String>>): TrackFields {
            fun joined(key: String) = TagValues.join(tags[key].orEmpty())
            return TrackFields(
                title = joined("TITLE"),
                album = joined("ALBUM"),
                date = joined("DATE"),
                artist = joined("ARTIST"),
                composer = joined("COMPOSER"),
                lyricist = joined("LYRICIST"),
                trackNumber = leadingNumber(tags["TRACKNUMBER"]?.firstOrNull()),
                discNumber = leadingNumber(tags["DISCNUMBER"]?.firstOrNull()),
                year = year(tags["DATE"]?.firstOrNull()),
            )
        }

        /** `"3"` and `"3/12"` give 3; anything not starting with a digit, or 0, gives null. */
        fun leadingNumber(value: String?): Int? {
            val digits = value?.takeWhile { it in '0'..'9' }.orEmpty()
            if (digits.isEmpty() || digits.length > 4) return null
            return digits.toInt().takeIf { it > 0 }
        }

        /** The year of `"2021"`, `"2021-05-03"` or `"2021-05-03T10:00"`; null if it doesn't start with four digits. */
        fun year(date: String?): Int? {
            if (date == null || date.length < 4 || !date.take(4).all { it in '0'..'9' }) return null
            if (date.length > 4 && date[4] in '0'..'9') return null
            return date.take(4).toInt().takeIf { it > 0 }
        }
    }
}
