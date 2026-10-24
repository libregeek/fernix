// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PlayingTag
import io.github.libregeek.fernix.data.db.TrackEntity
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.tags.PeopleTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One value on the playing track. A person's value opens Search filtered by that person. */
data class TagValue(val valueId: Long, val text: String, val isPerson: Boolean)

/** A Now Playing slot with its values: the key (or, for `PERFORMER:*`, the instrument) and every value. */
data class TagLine(val label: String, val values: List<TagValue>)

data class NowPlayingState(
    /** Null when nothing is playing or the track was deleted. */
    val track: TrackEntity? = null,
    val favourite: Boolean = false,
    val lines: List<TagLine> = emptyList(),
)

/**
 * Details of the playing track, all from the database and never from the file (spec §9.1): the
 * track row, the user's Now Playing tags and whether it is in Favourites, each a Room `Flow` so a
 * split or a change made elsewhere shows up at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModel(
    private val db: FernixDatabase,
    private val settings: Settings,
    private val playback: PlaybackController,
) : ViewModel() {

    private val trackId: Flow<Long?> = playback.state.map { it.trackId }.distinctUntilChanged()

    private val track: Flow<TrackEntity?> = trackId.flatMapLatest { id ->
        if (id == null) flowOf(null) else db.trackDao().observe(id)
    }

    private val favourite: Flow<Boolean> = trackId.flatMapLatest { id ->
        if (id == null) flowOf(false) else db.playlistDao().isFavourite(id)
    }

    /** The slot keys; re-read when the screen opens, since Settings may have changed them. */
    private val slotKeys = MutableStateFlow(settings.nowPlayingSlots)

    fun reloadSlots() {
        slotKeys.value = settings.nowPlayingSlots
    }

    private val lines: Flow<List<TagLine>> = combine(trackId, slotKeys, ::Pair).flatMapLatest { (id, keys) ->
        if (id == null) return@flatMapLatest flowOf(emptyList())
        val slots = keys.filter { it.isNotEmpty() }
        if (slots.isEmpty()) return@flatMapLatest flowOf(emptyList())
        flow { emit(db.tagDao().slotKeyIds(slots, includePerformer = PERFORMER in slots)) }
            .flatMapLatest { keyIds ->
                if (keyIds.isEmpty()) flowOf(emptyList())
                else db.tagDao().playingTags(id, keyIds).map { group(slots, it) }
            }
    }

    val state: StateFlow<NowPlayingState> = combine(track, favourite, lines, ::NowPlayingState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingState())

    /** The one-tap heart: adds the playing track to Favourites, or removes it. */
    fun toggleFavourite() {
        val id = playback.state.value.trackId ?: return
        viewModelScope.launch(Dispatchers.IO) { db.playlistDao().toggleFavourite(id, System.currentTimeMillis()) }
    }

    companion object {
        private const val PERFORMER = "PERFORMER"

        /**
         * Groups [tags] by slot, in slot order; tags a slot doesn't have are left out (spec §9.1).
         * The `PERFORMER` slot also takes every `PERFORMER:<instrument>` key and labels each
         * instrument's values with the instrument.
         */
        fun group(slots: List<String>, tags: List<PlayingTag>): List<TagLine> = slots.flatMap { slot ->
            val mine = tags.filter { it.key == slot || (slot == PERFORMER && it.key.startsWith("$PERFORMER:")) }
            mine.groupBy { it.key }.map { (key, rows) ->
                TagLine(
                    label = key.removePrefix("$PERFORMER:"),
                    values = rows.map { TagValue(it.valueId, it.value, PeopleTags.isPeopleKey(key)) },
                )
            }
        }
    }
}
