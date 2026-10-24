// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackStackTest {

    @Test
    fun startsOnTracks_thefirstTab() {
        val back = BackStack()
        assertEquals(Screen.Tracks, back.current)
        assertFalse(back.canPop)
    }

    @Test
    fun pushThenPop_returnsToPreviousScreen() {
        val back = BackStack()
        back.push(Screen.Folders)
        assertEquals(Screen.Folders, back.current)
        assertTrue(back.pop())
        assertEquals(Screen.Tracks, back.current)
    }

    @Test
    fun pop_onRoot_doesNothing() {
        val back = BackStack()
        assertFalse(back.pop())
        assertEquals(listOf(Screen.Tracks), back.stack)
    }

    @Test
    fun switchTab_clearsStack() {
        val back = BackStack()
        back.push(Screen.Folders)
        back.push(Screen.People)
        back.switchTab(Screen.Albums)
        assertEquals(listOf(Screen.Albums), back.stack)
    }

    @Test
    fun savedStateRestoresStack() {
        val back = BackStack(listOf(BackEntry(Screen.Albums), BackEntry(Screen.Tracks, arg = 42, arg2 = 7)))
        val saved = with(BackStack.Saver) {
            object : androidx.compose.runtime.saveable.SaverScope {
                override fun canBeSaved(value: Any) = true
            }.save(back)!!
        }
        val restored = BackStack.Saver.restore(saved)!!
        assertEquals(back.stack, restored.stack)
        assertEquals(42L, restored.currentArg)
        assertEquals(7L, restored.currentArg2)
    }

    @Test
    fun pushWithArgument_exposesItWhileOnTop() {
        val back = BackStack()
        back.push(Screen.AlbumDetail, 7)
        assertEquals(7L, back.currentArg)
        assertEquals(0L, back.currentArg2)
        back.push(Screen.Tracks, 5, 9)
        assertEquals(5L to 9L, back.currentArg to back.currentArg2)
        back.pop()
        back.pop()
        assertEquals(0L, back.currentArg)
    }

    @Test
    fun theBottomBarOrder_isTracksAlbumsFoldersPlaylistsPeople() {
        assertEquals(
            listOf(Screen.Tracks, Screen.Albums, Screen.Folders, Screen.Playlists, Screen.People),
            Screen.entries.filter { it.isTab },
        )
    }
}
