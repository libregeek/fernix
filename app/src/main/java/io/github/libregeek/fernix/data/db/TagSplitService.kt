// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.withTransaction
import io.github.libregeek.fernix.tags.TagSplitter
import io.github.libregeek.fernix.tags.TagValues

/** A tag value the user is splitting, with what it becomes. */
data class SplitCandidate(
    val keyId: Long,
    val key: String,
    val valueId: Long,
    val value: String,
    val parts: List<String>,
    /** Tracks in the whole library carrying it. */
    val libraryTracks: Int,
)

/**
 * Manual tag splitting (spec/architecture.md §5 *Tag splitting*): the user splits one value of a track,
 * which is applied to every track carrying it. Never touches the audio files.
 */
class TagSplitService(private val db: FernixDatabase) {

    private val summaries = SummaryRefresher(db)

    /**
     * What splitting [value] under [key] would do with the [delimiters]: the parts and how many tracks
     * in the library carry it. Null when it can't be split: TITLE, ALBUM and DATE never are, an unknown
     * key, or a value the delimiters leave as it is.
     */
    suspend fun candidateFor(key: String, valueId: Long, value: String, delimiters: List<String>): SplitCandidate? {
        if (key in TagSplitter.UNSPLITTABLE_KEYS) return null
        val parts = TagSplitter.split(value, delimiters)
        if (parts == listOf(value)) return null
        val keyId = db.tagDao().keyId(key) ?: return null
        return SplitCandidate(keyId, key, valueId, value, parts, db.tagDao().libraryTrackCount(keyId, valueId) ?: 0)
    }

    /**
     * Splits the candidate's value in every track that carries it (not just the one the user is looking
     * at), in one transaction. Nothing is remembered: a track whose file changes and is read again by
     * a scan shows what the file says.
     */
    suspend fun split(candidate: SplitCandidate) {
        db.withTransaction { applyOne(candidate) }
    }

    private suspend fun applyOne(candidate: SplitCandidate) {
        val tags = db.tagDao()
        val touched = LinkedHashSet<TagPair>()
        touched += TagPair(candidate.keyId, candidate.valueId)
        val partIds = HashMap<String, Long>()
        suspend fun idOf(text: String): Long = partIds.getOrPut(text.lowercase()) {
            val inserted = tags.insertValue(TagValueEntity(value = text))
            if (inserted > 0) inserted else tags.valueId(text)!!
        }

        for (trackId in tags.tracksWithValue(candidate.keyId, candidate.valueId)) {
            val before = tags.valuesOfTrackKey(trackId, candidate.keyId)
            val texts = TagSplitter.dedupe(
                before.flatMap { if (it.valueId == candidate.valueId) candidate.parts else listOf(it.value) },
            )
            tags.deleteTrackKey(trackId, candidate.keyId)
            tags.insertTrackTags(
                texts.mapIndexed { ord, text -> TrackTagEntity(trackId, candidate.keyId, idOf(text), ord) },
            )
            before.forEach { touched += TagPair(candidate.keyId, it.valueId) }
            texts.forEach { touched += TagPair(candidate.keyId, idOf(it)) }
            updateFixedColumn(trackId, candidate.key, texts)
        }
        summaries.refresh(touched)
        tags.deleteValueIfUnused(candidate.valueId)
    }

    /** The six fixed fields mirror the tags; of those only ARTIST, COMPOSER and LYRICIST can be split. */
    private suspend fun updateFixedColumn(trackId: Long, key: String, texts: List<String>) {
        val tracks = db.trackDao()
        val track = tracks.byId(trackId) ?: return
        val joined = TagValues.join(texts)
        val updated = when (key) {
            "ARTIST" -> track.copy(artist = joined)
            "COMPOSER" -> track.copy(composer = joined)
            "LYRICIST" -> track.copy(lyricist = joined)
            else -> return
        }
        tracks.update(updated)
    }
}
