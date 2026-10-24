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

data class AlbumsState(
    val loading: Boolean = true,
    val albums: List<AlbumEntity> = emptyList(),
    val empty: Boolean = false,
)

/** Albums by name then year, keyset-paged (spec/architecture.md §9). */
class AlbumsViewModel(
    private val db: FernixDatabase,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumsState())
    val state: StateFlow<AlbumsState> = _state

    private var done = false
    private var busy = false

    init {
        loadMore()
    }

    fun loadMore() {
        if (busy || done) return
        busy = true
        val last = _state.value.albums.lastOrNull()
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                db.albumDao().page(last?.album ?: "", last?.year ?: -1, PAGE_SIZE)
            }
            if (page.size < PAGE_SIZE) done = true
            busy = false
            _state.update {
                val all = it.albums + page
                it.copy(loading = false, albums = all, empty = done && all.isEmpty())
            }
        }
    }

    fun play(album: AlbumEntity) {
        viewModelScope.launch { tracks(album).let { if (it.isNotEmpty()) playback.play(it) } }
    }

    fun enqueue(album: AlbumEntity) {
        viewModelScope.launch { playback.enqueue(tracks(album)) }
    }

    private suspend fun tracks(album: AlbumEntity): List<TrackRow> =
        withContext(Dispatchers.IO) { db.trackDao().albumTracks(album.id) }
}
