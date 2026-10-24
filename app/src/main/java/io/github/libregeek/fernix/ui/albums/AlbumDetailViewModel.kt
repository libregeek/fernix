// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.AlbumEntity
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PAGE_SIZE
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlbumDetailState(
    val loading: Boolean = true,
    /** Null once loaded means the album no longer exists (e.g. removed by a rescan). */
    val album: AlbumEntity? = null,
    val tracks: List<TrackRow> = emptyList(),
)

/** Tracks of one album by disc then track number, keyset-paged (spec/architecture.md §9). */
class AlbumDetailViewModel(
    private val albumId: Long,
    private val db: FernixDatabase,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailState())
    val state: StateFlow<AlbumDetailState> = _state

    private var done = false
    private var busy = false

    init {
        viewModelScope.launch {
            val album = withContext(Dispatchers.IO) { db.albumDao().byId(albumId) }
            _state.update { it.copy(loading = album != null, album = album) }
            if (album != null) loadMore()
        }
    }

    fun loadMore() {
        if (busy || done) return
        busy = true
        val last = _state.value.tracks.lastOrNull()
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                // The row carries no disc/track number, so read them for the cursor.
                val entity = last?.let { db.trackDao().byId(it.id) }
                db.trackDao().albumPage(
                    albumId,
                    entity?.discNumber ?: 0,
                    entity?.trackNumber ?: 0,
                    last?.id ?: 0,
                    PAGE_SIZE,
                )
            }
            if (page.size < PAGE_SIZE) done = true
            busy = false
            _state.update { it.copy(loading = false, tracks = it.tracks + page) }
        }
    }

    /** Plays the whole album, starting at [track]. */
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

    private suspend fun allTracks(): List<TrackRow> =
        withContext(Dispatchers.IO) { db.trackDao().albumTracks(albumId) }
}
