// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PlaylistEntity
import io.github.libregeek.fernix.data.db.PlaylistRow
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Playlists tab shows: Favourites, then the user's own playlists. Null until loaded. */
data class PlaylistsState(
    val favourites: PlaylistRow?,
    val playlists: List<PlaylistRow>,
)

class PlaylistsViewModel(
    private val db: FernixDatabase,
    search: TrackSearch,
    private val playback: PlaybackController,
) : ViewModel() {

    private val tracks = PlaylistTracks(db, search)

    val state: StateFlow<PlaylistsState?> = combine(
        db.playlistDao().favouritesSummary(),
        db.playlistDao().summaries(),
    ) { favourites, playlists -> PlaylistsState(favourites, playlists) }
        .map<PlaylistsState, PlaylistsState?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            db.playlistDao().insert(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) { db.playlistDao().rename(id, name, System.currentTimeMillis()) }
    }

    fun delete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.playlistDao().delete(id) }
    }

    fun play(id: Long) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { tracks.load(id) }
            if (list.isNotEmpty()) playback.play(list)
        }
    }

    fun enqueue(id: Long) {
        viewModelScope.launch { playback.enqueue(withContext(Dispatchers.IO) { tracks.load(id) }) }
    }
}
