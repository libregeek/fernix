// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.albums

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.AlbumRow
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TagDao
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
    val albums: List<AlbumRow> = emptyList(),
    val empty: Boolean = false,
    /** What is typed in the search; the list is the albums whose name contains it. */
    val query: String = "",
)

/** Albums by name then year, keyset-paged (spec/architecture.md §9). */
class AlbumsViewModel(
    private val db: FernixDatabase,
    private val playback: PlaybackController,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumsState(query = saved[KEY_QUERY] ?: ""))
    val state: StateFlow<AlbumsState> = _state

    private var done = false
    private var busy = false

    /** Bumped on every restart so a page that arrives late is dropped. */
    private var generation = 0

    init {
        loadMore()
    }

    /** Starts the list again, e.g. after a scan added or removed albums. */
    fun refresh() {
        generation++
        done = false
        busy = false
        _state.value = AlbumsState(query = _state.value.query)
        loadMore()
    }

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        saved[KEY_QUERY] = query
        _state.update { it.copy(query = query) }
        refresh()
    }

    fun loadMore() {
        if (busy || done) return
        busy = true
        val last = _state.value.albums.lastOrNull()
        val pattern = TagDao.likeContains(_state.value.query)
        val mine = generation
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                db.albumDao().page(last?.album ?: "", last?.year ?: -1, pattern, PAGE_SIZE)
            }
            if (mine != generation) return@launch
            if (page.size < PAGE_SIZE) done = true
            busy = false
            _state.update {
                val all = it.albums + page
                it.copy(loading = false, albums = all, empty = done && all.isEmpty())
            }
        }
    }

    fun play(album: AlbumRow) {
        viewModelScope.launch { tracksOf(album).let { if (it.isNotEmpty()) playback.play(it) } }
    }

    fun enqueue(album: AlbumRow) {
        viewModelScope.launch { playback.enqueue(tracksOf(album)) }
    }

    suspend fun tracksOf(album: AlbumRow): List<TrackRow> =
        withContext(Dispatchers.IO) { db.trackDao().albumTracks(album.id) }

    private companion object {
        const val KEY_QUERY = "query"
    }
}
