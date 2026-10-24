// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import androidx.room.withTransaction
import io.github.libregeek.fernix.data.db.AlbumEntity
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.KeyValueEntity
import io.github.libregeek.fernix.data.db.PersonEntity
import io.github.libregeek.fernix.data.db.TagKeyEntity
import io.github.libregeek.fernix.data.db.TagPair
import io.github.libregeek.fernix.data.db.TagValueEntity
import io.github.libregeek.fernix.data.db.TrackEntity
import io.github.libregeek.fernix.data.db.TrackTagEntity
import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.tags.TagNormalizer
import io.github.libregeek.fernix.tags.TrackFields

/** One change to apply to the library (spec/architecture.md §6.4). */
sealed interface LibraryOp {
    /**
     * Insert the track, or replace it if [media]'s `mediaStoreId` is already stored. [rawTags] are
     * the tags as read from the file, before normalisation; null (unreadable file) stores the
     * track without tags.
     */
    class Upsert(val media: MediaRow, val rawTags: Map<String, List<String>>?) : LibraryOp

    data class Remove(val mediaStoreId: Long) : LibraryOp

    /** Sets `dateAdded` of a stored track without re-reading its file. */
    data class SetDateAdded(val mediaStoreId: Long, val dateAdded: Long) : LibraryOp
}

/**
 * Writes scan results in batches, one Room transaction per [apply] call (~500 ops, §6.4). Key and
 * value ids and album ids are cached for the life of the writer, i.e. one scan; use a new writer
 * per scan. Not thread-safe. Unused folders, albums and tag values are removed afterwards by the
 * post-scan cleanup, not here.
 */
class LibraryWriter(private val db: FernixDatabase, private val folders: FolderResolver) {

    private val keyIds = HashMap<String, Long>()
    private val valueIds = HashMap<String, Long>()
    private val albumIds = HashMap<Pair<String, Int>, Long>()

    suspend fun apply(ops: List<LibraryOp>) {
        db.withTransaction {
            val tracks = db.trackDao()
            val tags = db.tagDao()
            val touched = LinkedHashSet<TagPair>()
            val newRows = ArrayList<TrackTagEntity>()

            for (op in ops) when (op) {
                is LibraryOp.Remove -> tracks.idByMediaStoreId(op.mediaStoreId)?.let { id ->
                    touched += tracks.tagPairs(id)
                    tracks.deleteById(id)
                }
                is LibraryOp.SetDateAdded -> tracks.setDateAdded(op.mediaStoreId, op.dateAdded)
                is LibraryOp.Upsert -> {
                    val normalized = TagNormalizer.normalize(op.rawTags.orEmpty())
                    val fields = TrackFields.from(normalized)
                    val existing = tracks.idByMediaStoreId(op.media.mediaStoreId)
                    if (existing != null) {
                        touched += tracks.tagPairs(existing)
                        tracks.deleteTags(existing)
                    }
                    // A re-parse keeps the play history, which the scan doesn't own (spec §5, §6.4).
                    val previous = existing?.let { tracks.byId(it) }
                    val entity = trackEntity(op.media, fields, existing ?: 0).copy(
                        playCount = previous?.playCount ?: 0,
                        lastPlayedAt = previous?.lastPlayedAt,
                    )
                    val trackId = if (existing != null) existing.also { tracks.update(entity) } else tracks.insert(entity)
                    for ((key, values) in normalized) {
                        val keyId = keyId(key)
                        values.forEachIndexed { ord, value ->
                            val valueId = valueId(value)
                            newRows += TrackTagEntity(trackId, keyId, valueId, ord)
                            touched += TagPair(keyId, valueId)
                        }
                    }
                }
            }

            if (newRows.isNotEmpty()) tags.insertTrackTags(newRows)
            refreshSummaries(touched)
        }
    }

    private suspend fun trackEntity(media: MediaRow, fields: TrackFields, id: Long) = TrackEntity(
        id = id,
        mediaStoreId = media.mediaStoreId,
        folderId = folders.idFor(media.folderPath),
        displayName = media.displayName,
        durationMs = media.durationMs,
        dateModified = media.dateModified,
        sizeBytes = media.sizeBytes,
        dateAdded = media.dateAdded,
        title = fields.title,
        album = fields.album,
        date = fields.date,
        artist = fields.artist,
        composer = fields.composer,
        lyricist = fields.lyricist,
        trackNumber = fields.trackNumber,
        discNumber = fields.discNumber,
        year = fields.year,
        albumId = fields.albumKey?.let { albumId(it) },
    )

    private suspend fun albumId(key: Pair<String, Int>): Long = albumIds.getOrPut(key) {
        val dao = db.albumDao()
        dao.find(key.first, key.second)?.id ?: dao.insert(AlbumEntity(album = key.first, year = key.second))
    }

    private suspend fun keyId(key: String): Long = keyIds.getOrPut(key) {
        val dao = db.tagDao()
        val inserted = dao.insertKey(TagKeyEntity(key = key))
        if (inserted > 0) inserted else dao.keyId(key)!!
    }

    private suspend fun valueId(value: String): Long = valueIds.getOrPut(value) {
        val dao = db.tagDao()
        val inserted = dao.insertValue(TagValueEntity(value = value))
        if (inserted > 0) inserted else dao.valueId(value)!!
    }

    /** Recomputes `key_values` and `people` for just the pairs and values a batch touched. */
    private suspend fun refreshSummaries(touched: Set<TagPair>) {
        val tags = db.tagDao()
        val peopleKeys = tags.peopleKeyIds(PeopleTags.KEYS.toList())
        for ((keyId, valueId) in touched) {
            val count = tags.countTracks(keyId, valueId)
            if (count == 0) tags.deleteKeyValue(keyId, valueId)
            else tags.upsertKeyValues(listOf(KeyValueEntity(keyId, valueId, count)))
        }
        if (peopleKeys.isEmpty()) return
        for (valueId in touched.mapTo(HashSet()) { it.valueId }) {
            val count = tags.countPersonTracks(valueId, peopleKeys)
            if (count == 0) tags.deletePerson(valueId)
            else tags.upsertPeople(listOf(PersonEntity(valueId, count)))
        }
    }
}
