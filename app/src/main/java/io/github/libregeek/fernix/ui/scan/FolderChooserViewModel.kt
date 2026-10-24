// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.scan.CheckState
import io.github.libregeek.fernix.data.scan.FolderTree
import io.github.libregeek.fernix.data.scan.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One visible row of the chooser. */
data class FolderRow(
    val path: String,
    val name: String,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val state: CheckState,
)

data class FolderChooserState(
    val loading: Boolean = true,
    val rows: List<FolderRow> = emptyList(),
    val selected: Set<String> = emptySet(),
)

/** Spec/architecture.md §6, "In-app folder chooser". */
class FolderChooserViewModel(
    private val media: MediaSource,
    initialSelection: Set<String>,
    /** The ticked folders are kept here, so a half-made choice survives the process being killed. */
    private val saved: SavedStateHandle,
) : ViewModel() {

    private var tree = FolderTree(emptyList())
    private var selected: Set<String> = saved.get<ArrayList<String>>(KEY_SELECTED)?.toSet() ?: initialSelection
    private val expanded = HashSet<String>()

    private val _state = MutableStateFlow(FolderChooserState(selected = selected))
    val state: StateFlow<FolderChooserState> = _state

    init {
        viewModelScope.launch {
            tree = withContext(Dispatchers.IO) { FolderTree(media.folders(media.mountedVolumes())) }
            // With a single volume, start one level down (spec §5, folders).
            if (tree.roots.size == 1) expanded += tree.roots.single().path
            publish(loading = false)
        }
    }

    fun toggleSelected(path: String) {
        selected = tree.toggle(selected, path)
        saved[KEY_SELECTED] = ArrayList(selected)
        publish()
    }

    fun toggleExpanded(path: String) {
        if (!expanded.remove(path)) expanded += path
        publish()
    }

    private fun publish(loading: Boolean = false) {
        val rows = ArrayList<FolderRow>()
        fun add(node: FolderTree.Node, depth: Int) {
            val isExpanded = node.path in expanded
            rows += FolderRow(node.path, node.name, depth, node.children.isNotEmpty(), isExpanded, tree.checkState(selected, node.path))
            if (isExpanded) node.children.forEach { add(it, depth + 1) }
        }
        tree.roots.forEach { add(it, 0) }
        _state.update { FolderChooserState(loading, rows, selected) }
    }

    private companion object {
        const val KEY_SELECTED = "selected"
    }
}
