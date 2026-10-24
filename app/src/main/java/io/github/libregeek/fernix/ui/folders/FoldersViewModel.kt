// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.folders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.FolderEntity
import io.github.libregeek.fernix.data.db.FolderPaths
import io.github.libregeek.fernix.data.db.PAGE_SIZE
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FoldersState(
    val loading: Boolean = true,
    /** The folders opened so far, outermost first. Empty means the list of volumes. */
    val path: List<FolderEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val tracks: List<TrackRow> = emptyList(),
    val canGoUp: Boolean = false,
    /** Everything is loaded and there is nothing to show. */
    val empty: Boolean = false,
)

/**
 * Folder browser over the `folders` tree (spec/architecture.md §9): sub-folders first, then the
 * tracks directly in the folder, both keyset-paged. With a single volume the list of volumes is
 * skipped and the volume's own content is the top level.
 */
class FoldersViewModel(
    private val db: FernixDatabase,
    private val playback: PlaybackController,
    /** The ids of the folders opened so far, so the place survives the process being killed. */
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(FoldersState())
    val state: StateFlow<FoldersState> = _state

    /** Set when the library has a single volume: that volume is the top level. */
    private var lockedRoot: FolderEntity? = null
    private var stack = emptyList<FolderEntity>()
    private var foldersDone = false
    private var tracksDone = false
    private var busy = false

    /** Bumped on every navigation so a page that arrives late is dropped. */
    private var generation = 0

    init {
        viewModelScope.launch {
            val ids = saved.get<ArrayList<Long>>(KEY_PATH).orEmpty()
            if (ids.isNotEmpty()) {
                // Folders a scan has removed since drop out; the rest is the path as it was.
                stack = withContext(Dispatchers.IO) { ids.mapNotNull { db.folderDao().byId(it) } }
            }
            refresh()
        }
    }

    /**
     * Loads the volumes and the open folder again, e.g. after a scan added or removed some. The
     * folder you are in is kept; the top level follows the volumes (a single volume is skipped).
     */
    fun refresh() {
        viewModelScope.launch {
            val volumes = withContext(Dispatchers.IO) { db.folderDao().volumes() }
            lockedRoot = volumes.singleOrNull()
            if (stack.isEmpty()) lockedRoot?.let { stack = listOf(it) }
            reset(volumes)
        }
    }

    fun open(folder: FolderEntity) {
        stack = stack + folder
        savePath()
        viewModelScope.launch { reset(null) }
    }

    private fun savePath() {
        saved[KEY_PATH] = ArrayList(stack.map { it.id })
    }

    fun up(): Boolean {
        if (stack.size <= (if (lockedRoot != null) 1 else 0)) return false
        stack = stack.dropLast(1)
        savePath()
        viewModelScope.launch { reset(null) }
        return true
    }

    private suspend fun reset(volumes: List<FolderEntity>?) {
        generation++
        busy = false
        foldersDone = false
        tracksDone = false
        _state.value = FoldersState(path = stack, canGoUp = stack.size > (if (lockedRoot != null) 1 else 0))
        if (stack.isEmpty()) {
            // The list of volumes: few, so no paging.
            val all = volumes ?: withContext(Dispatchers.IO) { db.folderDao().volumes() }
            foldersDone = true
            tracksDone = true
            _state.update { it.copy(loading = false, folders = all, empty = all.isEmpty()) }
        } else {
            loadMore()
        }
    }

    /** Loads the next page: sub-folders until there are no more, then tracks. */
    fun loadMore() {
        val folder = stack.lastOrNull() ?: return
        if (busy || (foldersDone && tracksDone)) return
        busy = true
        val gen = generation
        val current = _state.value
        viewModelScope.launch {
            val loadFolders = !foldersDone
            val folders: List<FolderEntity>
            val tracks: List<TrackRow>
            withContext(Dispatchers.IO) {
                if (loadFolders) {
                    val last = current.folders.lastOrNull()
                    folders = db.folderDao().childrenPage(folder.id, last?.name ?: "", last?.id ?: 0, PAGE_SIZE)
                    tracks = emptyList()
                } else {
                    val last = current.tracks.lastOrNull()
                    val afterName = last?.let { db.trackDao().byId(it.id)?.displayName } ?: ""
                    folders = emptyList()
                    tracks = db.trackDao().folderPage(folder.id, afterName, last?.id ?: 0, PAGE_SIZE)
                }
            }
            if (gen != generation) return@launch
            if (loadFolders && folders.size < PAGE_SIZE) foldersDone = true
            if (!loadFolders && tracks.size < PAGE_SIZE) tracksDone = true
            busy = false
            _state.update {
                val f = it.folders + folders
                val t = it.tracks + tracks
                it.copy(
                    loading = false,
                    folders = f,
                    tracks = t,
                    empty = foldersDone && tracksDone && f.isEmpty() && t.isEmpty(),
                )
            }
            // A page of sub-folders may have been the last one and short: carry on with tracks
            // right away so a folder with few sub-folders still shows its tracks.
            if (loadFolders && foldersDone) loadMore()
        }
    }

    /** Plays the tracks directly in the open folder, starting at [track]. */
    fun playTrack(track: TrackRow) {
        val folder = stack.lastOrNull() ?: return
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { db.trackDao().folderTracks(folder.id) }
            playback.play(all, all.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        }
    }

    fun enqueueTrack(track: TrackRow) = playback.enqueue(listOf(track))

    /** Plays a folder and everything below it, folder by folder. */
    fun playFolder(folder: FolderEntity) {
        viewModelScope.launch { subtree(folder).let { if (it.isNotEmpty()) playback.play(it) } }
    }

    fun enqueueFolder(folder: FolderEntity) {
        viewModelScope.launch { playback.enqueue(subtree(folder)) }
    }

    /** Every track in [folder] and below, folder by folder. */
    suspend fun subtree(folder: FolderEntity): List<TrackRow> = withContext(Dispatchers.IO) {
        val (low, high) = FolderPaths.subtreeRange(folder.path)
        db.trackDao().subtreeTracks(folder.path, low, high)
    }

    private companion object {
        const val KEY_PATH = "path"
    }
}
