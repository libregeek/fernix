// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PlaylistEntity
import io.github.libregeek.fernix.data.db.PlaylistRow
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `null` until the first emission, so "no playlists yet" isn't shown while loading. */
class PlaylistsViewModel(
    private val db: FernixDatabase,
    private val playback: PlaybackController,
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistRow>?> = db.playlistDao().summaries()
        .map<List<PlaylistRow>, List<PlaylistRow>?> { it }
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
            val tracks = withContext(Dispatchers.IO) { db.playlistDao().tracks(id) }
            if (tracks.isNotEmpty()) playback.play(tracks)
        }
    }

    fun enqueue(id: Long) {
        viewModelScope.launch { playback.enqueue(withContext(Dispatchers.IO) { db.playlistDao().tracks(id) }) }
    }
}
