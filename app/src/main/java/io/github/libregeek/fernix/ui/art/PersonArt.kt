// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.art

import android.os.ParcelFileDescriptor
import coil3.ImageLoader
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.key.Keyer
import coil3.request.Options
import io.github.libregeek.fernix.tags.Picture
import io.github.libregeek.fernix.tags.TagReader

/**
 * Coil model for a person's image (spec/architecture.md §7 *Person images*): an embedded picture
 * whose type is a role, found among the person's first tracks.
 */
data class PersonArt(val personId: Long, val name: String)

class PersonArtKeyer : Keyer<PersonArt> {
    override fun key(data: PersonArt, options: Options): String = "person-art:${data.personId}"
}

/** A track of the person: its file and its values under the role keys, for matching pictures. */
class PersonArtTrack(val mediaStoreId: Long, val valuesByKey: Map<String, List<String>>)

/** What the fetcher needs from the database. */
interface PersonArtSource {
    /** The person's tracks in `id` order, at most [limit], with their values under [PersonPicture.KEYS]. */
    suspend fun tracks(personId: Long, limit: Int): List<PersonArtTrack>

    /** The people tag key the person appears under on most tracks, or null. */
    suspend fun topRoleKey(personId: Long): String?
}

object PersonPicture {
    /** Picture type → people tag key (spec §7). Cover (3) and every other type never counts. */
    fun keyOf(type: Int): String? = when (type) {
        7, 8, 10 -> "ARTIST"
        9 -> "CONDUCTOR"
        11 -> "COMPOSER"
        12 -> "LYRICIST"
        else -> null
    }

    val KEYS = listOf("ARTIST", "CONDUCTOR", "COMPOSER", "LYRICIST")

    const val MAX_TRACKS = 20

    /**
     * Whether [picture] shows [person]: it is role-typed, and either its description is the person's
     * name, or the track has a single value under the mapped key and that is the person. With several
     * people under the key and no description it is ignored rather than guessed.
     */
    fun matches(picture: Picture, person: String, valuesUnderKey: List<String>): Boolean {
        if (keyOf(picture.type) == null) return false
        if (picture.description.equals(person, ignoreCase = true)) return true
        return picture.description.isEmpty() && valuesUnderKey.singleOrNull()?.equals(person, ignoreCase = true) == true
    }

}

class PersonArtFetcher(
    private val source: PersonArtSource,
    private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    private val data: PersonArt,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val top = source.topRoleKey(data.personId)
        var first: Picture? = null
        for (track in source.tracks(data.personId, PersonPicture.MAX_TRACKS)) {
            val pictures = open(track.mediaStoreId)?.let(TagReader::readPictures) ?: continue
            for (picture in pictures) {
                val key = PersonPicture.keyOf(picture.type) ?: continue
                if (!PersonPicture.matches(picture, data.name, track.valuesByKey[key].orEmpty())) continue
                if (key == top) return result(picture)
                if (first == null) first = picture
            }
        }
        return first?.let(::result)
    }

    class Factory(
        private val source: PersonArtSource,
        private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    ) : Fetcher.Factory<PersonArt> {
        override fun create(data: PersonArt, options: Options, imageLoader: ImageLoader): Fetcher =
            PersonArtFetcher(source, open, data)
    }
}
