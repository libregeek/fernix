// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTreeTest {

    private val tree = FolderTree(
        listOf(
            "external_primary/Music/Rock/Live",
            "external_primary/Music/Jazz",
            "external_primary/Podcasts",
            "1234-5678",
            "1234-5678/Albums",
        ),
    )

    @Test
    fun parentsAreAddedAndSortedIgnoringCase() {
        assertEquals(listOf("1234-5678", "external_primary"), tree.roots.map { it.name })
        val music = tree.node("external_primary/Music")!!
        assertEquals(listOf("Jazz", "Rock"), music.children.map { it.name })
        assertEquals(listOf("Music", "Podcasts"), tree.node("external_primary")!!.children.map { it.name })
        assertEquals(listOf("Albums"), tree.node("1234-5678")!!.children.map { it.name })
    }

    @Test
    fun emptyTree() {
        assertEquals(emptyList<FolderTree.Node>(), FolderTree(emptyList()).roots)
    }

    @Test
    fun checkStates() {
        val selected = setOf("external_primary/Music", "1234-5678/Albums")
        assertEquals(CheckState.Checked, tree.checkState(selected, "external_primary/Music"))
        assertEquals(CheckState.Covered, tree.checkState(selected, "external_primary/Music/Rock/Live"))
        assertEquals(CheckState.Partial, tree.checkState(selected, "external_primary"))
        assertEquals(CheckState.Partial, tree.checkState(selected, "1234-5678"))
        assertEquals(CheckState.Unchecked, tree.checkState(selected, "external_primary/Podcasts"))
    }

    @Test
    fun siblingWithSamePrefixIsNotCovered() {
        val t = FolderTree(listOf("v/Music", "v/Music2"))
        assertEquals(CheckState.Unchecked, t.checkState(setOf("v/Music"), "v/Music2"))
    }

    @Test
    fun tickingParentDropsRedundantChildren() {
        val selected = setOf("external_primary/Music/Jazz", "external_primary/Podcasts")
        assertEquals(
            setOf("external_primary/Music", "external_primary/Podcasts"),
            tree.toggle(selected, "external_primary/Music"),
        )
    }

    @Test
    fun tickingVolumeSelectsWholeVolume() {
        assertEquals(setOf("1234-5678"), tree.toggle(setOf("1234-5678/Albums"), "1234-5678"))
    }

    @Test
    fun untickingRemovesIt() {
        assertEquals(emptySet<String>(), tree.toggle(setOf("external_primary/Music"), "external_primary/Music"))
    }

    @Test
    fun coveredFolderCannotBeToggled() {
        val selected = setOf("external_primary/Music")
        assertEquals(selected, tree.toggle(selected, "external_primary/Music/Jazz"))
    }

    @Test
    fun partialParentCanBeTicked() {
        val selected = setOf("external_primary/Music/Jazz")
        assertEquals(CheckState.Partial, tree.checkState(selected, "external_primary/Music"))
        assertEquals(setOf("external_primary/Music"), tree.toggle(selected, "external_primary/Music"))
    }
}
