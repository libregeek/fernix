// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

enum class CheckState {
    Unchecked,

    /** Some folder below is selected, this one is not. */
    Partial,

    /** Selected itself. */
    Checked,

    /** Included because an ancestor is selected; cleared by unticking that ancestor. */
    Covered,
}

/**
 * The folders of the in-app chooser (spec/architecture.md §6), built from the distinct
 * `<volume>/<relativePath>` strings of the audio table. Folders that only hold sub-folders with
 * audio are included as parents.
 */
class FolderTree(folderPaths: Collection<String>) {

    class Node(val path: String, val name: String) {
        internal val childList = ArrayList<Node>()
        val children: List<Node> get() = childList
    }

    private val nodes = HashMap<String, Node>()
    val roots: List<Node>

    init {
        val roots = ArrayList<Node>()
        for (path in folderPaths) ensure(path, roots)
        roots.sortBy { it.name.lowercase() }
        nodes.values.forEach { n -> n.childList.sortBy { it.name.lowercase() } }
        this.roots = roots
    }

    private fun ensure(path: String, roots: MutableList<Node>): Node {
        nodes[path]?.let { return it }
        val slash = path.lastIndexOf('/')
        val node = Node(path, path.substring(slash + 1))
        nodes[path] = node
        if (slash < 0) roots += node else ensure(path.substring(0, slash), roots).childList += node
        return node
    }

    fun node(path: String): Node? = nodes[path]

    fun checkState(selected: Set<String>, path: String): CheckState {
        if (path in selected) return CheckState.Checked
        if (ancestors(path).any { it in selected }) return CheckState.Covered
        if (selected.any { it.startsWith("$path/") }) return CheckState.Partial
        return CheckState.Unchecked
    }

    /**
     * Ticks or unticks [path]. Ticking selects it and drops now-redundant selections below it;
     * unticking removes it. A [CheckState.Covered] folder can't be toggled (its ancestor must be
     * unticked), since a selection can't express "all of this except that".
     */
    fun toggle(selected: Set<String>, path: String): Set<String> = when (checkState(selected, path)) {
        CheckState.Checked -> selected - path
        CheckState.Covered -> selected
        CheckState.Unchecked, CheckState.Partial -> selected.filterNot { it.startsWith("$path/") }.toSet() + path
    }

    private fun ancestors(path: String): Sequence<String> =
        generateSequence(path.substringBeforeLast('/', "").ifEmpty { null }) { it.substringBeforeLast('/', "").ifEmpty { null } }
}
