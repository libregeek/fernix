// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver

/** Screens of the app. Detail screens are added together with the features that need them. */
enum class Screen(val isTab: Boolean) {
    Folders(true),
    Search(true),
    Albums(true),
    Playlists(true),
    People(true),
}

/**
 * The hand-rolled back stack (spec/architecture.md §2). Switching tab clears the stack so the
 * tab becomes its root; [push] adds a screen on top (e.g. Search opened from Now Playing).
 */
class BackStack(initial: List<Screen> = listOf(Screen.Folders)) {

    private val entries = mutableStateListOf<Screen>().apply { addAll(initial) }

    val stack: List<Screen> get() = entries.toList()
    val current: Screen get() = entries.last()
    val canPop: Boolean get() = entries.size > 1

    fun push(screen: Screen) {
        entries.add(screen)
    }

    fun pop(): Boolean {
        if (!canPop) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    fun switchTab(tab: Screen) {
        require(tab.isTab) { "$tab is not a tab" }
        entries.clear()
        entries.add(tab)
    }

    companion object {
        val Saver = listSaver<BackStack, String>(
            save = { back -> back.stack.map { it.name } },
            restore = { names -> BackStack(names.map { Screen.valueOf(it) }) },
        )
    }
}
