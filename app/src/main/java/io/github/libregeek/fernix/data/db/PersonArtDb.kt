// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.ui.art.PersonArtSource
import io.github.libregeek.fernix.ui.art.PersonArtTrack
import io.github.libregeek.fernix.ui.art.PersonPicture

/** Gives the person-image fetcher the person's tracks and role from the database. */
class PersonArtDb(private val db: FernixDatabase) : PersonArtSource {

    override suspend fun tracks(personId: Long, limit: Int): List<PersonArtTrack> {
        val peopleKeys = db.tagDao().peopleKeyIds(PeopleTags.KEYS.toList())
        if (peopleKeys.isEmpty()) return emptyList()
        return db.trackDao().personTrackFiles(personId, peopleKeys, limit).map { file ->
            val values = db.tagDao().valuesOfKeys(file.id, PersonPicture.KEYS).groupBy({ it.key }, { it.value })
            PersonArtTrack(file.mediaStoreId, values)
        }
    }

    override suspend fun topRoleKey(personId: Long): String? {
        val peopleKeys = db.tagDao().peopleKeyIds(PeopleTags.KEYS.toList())
        return db.tagDao().roleCounts(personId, peopleKeys).maxByOrNull { it.trackCount }?.key
    }
}
