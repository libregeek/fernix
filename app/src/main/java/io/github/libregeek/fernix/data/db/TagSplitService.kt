// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import androidx.room.withTransaction
import io.github.libregeek.fernix.tags.TagSplitter
import io.github.libregeek.fernix.tags.TagValues

/** A (key, value) the preview offers to split, with what it would become. */
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
 * Manual tag splitting (spec/architecture.md §5 *Tag splitting*): preview over the tracks of a list,
 * and apply as split rules library-wide. Never touches the audio files.
 */
class TagSplitService(private val db: FernixDatabase) {

    private val summaries = SummaryRefresher(db)

    /**
     * Each distinct (key, value) of [keyIds] on [trackIds] that holds one of the [separators], sorted
     * by key then value. The lookup runs in chunks, so the list may be as long as the library.
     */
    suspend fun preview(trackIds: List<Long>, keyIds: List<Long>, separators: List<String>): List<SplitCandidate> {
        if (trackIds.isEmpty() || keyIds.isEmpty() || separators.none { it.isNotEmpty() }) return emptyList()
        val found = LinkedHashMap<Pair<Long, Long>, TagValueRef>()
        for (chunk in trackIds.chunked(CHUNK)) {
            for (ref in db.tagDao().valuesOnTracks(chunk, keyIds)) {
                if (TagSplitter.contains(ref.value, separators)) found.putIfAbsent(ref.keyId to ref.valueId, ref)
            }
        }
        return found.values
            .map { ref ->
                SplitCandidate(
                    ref.keyId, ref.key, ref.valueId, ref.value,
                    TagSplitter.split(ref.value, separators),
                    db.tagDao().libraryTrackCount(ref.keyId, ref.valueId) ?: 0,
                )
            }
            .sortedWith(compareBy({ it.key }, { it.value.lowercase() }))
    }

    /**
     * Creates one rule per candidate and applies it to every track carrying the value, one
     * transaction per rule. [onProgress] gets (rules done, rules total) after each.
     */
    suspend fun apply(candidates: List<SplitCandidate>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        candidates.forEachIndexed { index, candidate ->
            db.withTransaction { applyOne(candidate) }
            onProgress(index + 1, candidates.size)
        }
    }

    private suspend fun applyOne(candidate: SplitCandidate) {
        val tags = db.tagDao()
        val rules = db.splitRuleDao()
        val ruleId = rules.find(candidate.keyId, candidate.value)?.id ?: rules.insert(
            SplitRuleEntity(
                keyId = candidate.keyId,
                value = candidate.value,
                parts = TagValues.join(candidate.parts),
                createdAt = System.currentTimeMillis(),
            ),
        )
        val touched = LinkedHashSet<TagPair>()
        touched += TagPair(candidate.keyId, candidate.valueId)
        val partIds = HashMap<String, Long>()
        suspend fun idOf(text: String): Long = partIds.getOrPut(text.lowercase()) {
            val inserted = tags.insertValue(TagValueEntity(value = text))
            if (inserted > 0) inserted else tags.valueId(text)!!
        }

        val trackIds = tags.tracksWithValue(candidate.keyId, candidate.valueId)
        for (trackId in trackIds) {
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
        rules.insertRuleTracks(trackIds.map { SplitRuleTrackEntity(ruleId, it) })
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

    private companion object {
        /** Track ids per query; SQLite allows 999 variables on Android 10 and the key ids share them. */
        const val CHUNK = 400
    }
}
