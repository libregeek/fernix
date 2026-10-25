// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

/**
 * The people of a track as the lock screen and the mini player show them (spec/architecture.md §9):
 * composers, then lyricists, then artists, a name that appears twice kept once (ignoring case) in its
 * first place.
 */
object Credits {

    /** The most people the lock screen shows. */
    const val LOCK_SCREEN_MAX = 6

    fun people(composer: String, lyricist: String, artist: String, max: Int = Int.MAX_VALUE): List<String> {
        val seen = HashSet<String>()
        return (TagValues.split(composer) + TagValues.split(lyricist) + TagValues.split(artist))
            .filter { seen.add(it.lowercase()) }
            .take(max)
    }

    /** `Name; Name; Name`, or null when there is nobody to show. */
    fun line(people: List<String>): String? = people.joinToString("; ").ifEmpty { null }

    /** The second line of the lock screen: the album, then the people (`Album · A; B; C`). */
    fun lockScreenLine(album: String, composer: String, lyricist: String, artist: String): String? {
        val albumName = TagValues.split(album).joinToString("; ").ifEmpty { null }
        val names = line(people(composer, lyricist, artist, LOCK_SCREEN_MAX))
        return listOfNotNull(albumName, names).joinToString(" · ").ifEmpty { null }
    }
}
