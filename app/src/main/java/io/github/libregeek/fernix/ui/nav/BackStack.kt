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
    NowPlaying(false),
    /** Takes the album id as its argument. */
    AlbumDetail(false),
}

/**
 * The hand-rolled back stack (spec/architecture.md §2). Switching tab clears the stack so the
 * tab becomes its root; [push] adds a screen on top (e.g. Search opened from Now Playing).
 */
class BackStack(initial: List<Pair<Screen, Long>> = listOf(Screen.Folders to 0L)) {

    private val entries = mutableStateListOf<Pair<Screen, Long>>().apply { addAll(initial) }

    val stack: List<Screen> get() = entries.map { it.first }
    val current: Screen get() = entries.last().first
    /** The argument of [current], e.g. the album id of [Screen.AlbumDetail]; 0 when it has none. */
    val currentArg: Long get() = entries.last().second
    val canPop: Boolean get() = entries.size > 1

    fun push(screen: Screen, arg: Long = 0) {
        entries.add(screen to arg)
    }

    fun pop(): Boolean {
        if (!canPop) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    fun switchTab(tab: Screen) {
        require(tab.isTab) { "$tab is not a tab" }
        entries.clear()
        entries.add(tab to 0L)
    }

    companion object {
        val Saver = listSaver<BackStack, String>(
            save = { back -> back.entries.map { (screen, arg) -> "${screen.name}:$arg" } },
            restore = { saved ->
                BackStack(saved.map { it.substringBefore(':').let(Screen::valueOf) to it.substringAfter(':').toLong() })
            },
        )
    }
}
