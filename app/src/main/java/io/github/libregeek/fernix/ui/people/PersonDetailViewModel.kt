// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PAGE_SIZE
import io.github.libregeek.fernix.data.db.RoleCount
import io.github.libregeek.fernix.data.db.TrackCursor
import io.github.libregeek.fernix.data.db.TrackFilter
import io.github.libregeek.fernix.data.db.TrackResult
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.tags.PeopleTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PersonDetailState(
    val loading: Boolean = true,
    /** Null once loaded means the person no longer exists (removed by a rescan or a split). */
    val name: String? = null,
    val roles: List<RoleCount> = emptyList(),
    val tracks: List<TrackResult> = emptyList(),
)

/** A person's roles and every track they contributed to, in any role (spec/architecture.md §9). */
class PersonDetailViewModel(
    private val personId: Long,
    private val db: FernixDatabase,
    private val search: TrackSearch,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(PersonDetailState())
    val state: StateFlow<PersonDetailState> = _state

    private val filter = TrackFilter(personValueId = personId)
    private var done = false
    private var busy = false

    init {
        viewModelScope.launch {
            val (name, roles) = withContext(Dispatchers.IO) {
                val keys = db.tagDao().peopleKeyIds(PeopleTags.KEYS.toList())
                db.tagDao().valueText(personId) to db.tagDao().roleCounts(personId, keys)
            }
            _state.update { it.copy(loading = name != null, name = name, roles = roles) }
            if (name != null) loadMore()
        }
    }

    fun loadMore() {
        if (busy || done) return
        busy = true
        val last = _state.value.tracks.lastOrNull()?.track
        val cursor = last?.let { TrackCursor(it.title, it.id) } ?: TrackCursor.START
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) { search.page(filter, cursor) }
            if (page.size < PAGE_SIZE) done = true
            busy = false
            _state.update { it.copy(loading = false, tracks = it.tracks + page) }
        }
    }

    /** Plays every track of the person, starting at [track]. */
    fun playFrom(track: TrackRow) {
        viewModelScope.launch {
            val all = allTracks()
            playback.play(all, all.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        }
    }

    fun playAll() {
        viewModelScope.launch { allTracks().let { if (it.isNotEmpty()) playback.play(it) } }
    }

    fun enqueueAll() {
        viewModelScope.launch { playback.enqueue(allTracks()) }
    }

    fun enqueueTrack(track: TrackRow) = playback.enqueue(listOf(track))

    private suspend fun allTracks(): List<TrackRow> = withContext(Dispatchers.IO) { search.all(filter) }
}
