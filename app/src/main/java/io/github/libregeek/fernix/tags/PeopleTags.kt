// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

/** The tag keys whose values are people (spec/architecture.md §5, "People tag keys"). */
object PeopleTags {

    /** Exact keys, as TagLib's PropertyMap reports them (uppercase). */
    val KEYS: Set<String> = linkedSetOf(
        "ARTIST", "ALBUMARTIST", "AUTHOR", "COMPOSER", "LYRICIST", "WRITER", "CONDUCTOR", "REMIXER",
        "ARRANGER", "PRODUCER", "ENGINEER", "MIXER", "DJMIXER", "ORIGINALARTIST",
        "ORIGINALLYRICIST", "PERFORMER",
    )

    /** TagLib maps ID3v2.4 TMCL musician credits to `PERFORMER:<instrument>`. */
    const val PERFORMER_PREFIX = "PERFORMER:"

    fun isPeopleKey(key: String): Boolean = key in KEYS || key.startsWith(PERFORMER_PREFIX)
}
