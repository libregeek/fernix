// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver

/**
 * Screens of the app. The tabs come first, in the order of the bottom bar (the first is where the app
 * starts); detail screens are added together with the features that need them.
 */
enum class Screen(val isTab: Boolean) {
    Tracks(true),
    Albums(true),
    Folders(true),
    Playlists(true),
    People(true),
    NowPlaying(false),
    Queue(false),
    /** Takes `tracks.id` as its argument. */
    TrackDetail(false),
    Settings(false),
    LibraryFolders(false),
    ProblemFiles(false),
    /** Takes the album id as its argument. */
    AlbumDetail(false),
    /** Takes the person's `tag_values.id` as its argument. */
    PersonDetail(false),
    /** Takes the playlist id as its argument. */
    PlaylistDetail(false),
}

/**
 * One place in the back stack: a [screen] with up to two numeric arguments. Tracks takes a person's
 * value id as [arg] (a person filter), or a tag filter as [arg] = value id and [arg2] = tag key id.
 */
data class BackEntry(val screen: Screen, val arg: Long = 0, val arg2: Long = 0)

/**
 * The hand-rolled back stack (spec/architecture.md §2). Switching tab clears the stack so the
 * tab becomes its root; [push] adds a screen on top (e.g. Tracks opened from Now Playing).
 */
class BackStack(initial: List<BackEntry> = listOf(BackEntry(Screen.Tracks))) {

    private val entries = mutableStateListOf<BackEntry>().apply { addAll(initial) }

    val stack: List<Screen> get() = entries.map { it.screen }
    val current: Screen get() = entries.last().screen
    /** The argument of [current], e.g. the album id of [Screen.AlbumDetail]; 0 when it has none. */
    val currentArg: Long get() = entries.last().arg
    /** The second argument of [current]; 0 when it has none. */
    val currentArg2: Long get() = entries.last().arg2
    val canPop: Boolean get() = entries.size > 1

    fun push(screen: Screen, arg: Long = 0, arg2: Long = 0) {
        entries.add(BackEntry(screen, arg, arg2))
    }

    fun pop(): Boolean {
        if (!canPop) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /**
     * Shows Now Playing: pops back to it if it is already in the stack (a play started from a list
     * that was opened from Now Playing), else pushes it.
     */
    fun showNowPlaying() {
        val at = entries.indexOfLast { it.screen == Screen.NowPlaying }
        if (at >= 0) {
            while (entries.lastIndex > at) entries.removeAt(entries.lastIndex)
        } else {
            push(Screen.NowPlaying)
        }
    }

    fun switchTab(tab: Screen) {
        require(tab.isTab) { "$tab is not a tab" }
        entries.clear()
        entries.add(BackEntry(tab))
    }

    companion object {
        val Saver = listSaver<BackStack, String>(
            save = { back -> back.entries.map { "${it.screen.name}:${it.arg}:${it.arg2}" } },
            restore = { saved ->
                BackStack(
                    saved.map {
                        val (name, arg, arg2) = it.split(':')
                        BackEntry(Screen.valueOf(name), arg.toLong(), arg2.toLong())
                    },
                )
            },
        )
    }
}
