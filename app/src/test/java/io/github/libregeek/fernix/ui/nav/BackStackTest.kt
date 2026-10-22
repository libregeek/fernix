// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackStackTest {

    @Test
    fun startsOnFolders() {
        val back = BackStack()
        assertEquals(Screen.Folders, back.current)
        assertFalse(back.canPop)
    }

    @Test
    fun pushThenPop_returnsToPreviousScreen() {
        val back = BackStack()
        back.push(Screen.Search)
        assertEquals(Screen.Search, back.current)
        assertTrue(back.pop())
        assertEquals(Screen.Folders, back.current)
    }

    @Test
    fun pop_onRoot_doesNothing() {
        val back = BackStack()
        assertFalse(back.pop())
        assertEquals(listOf(Screen.Folders), back.stack)
    }

    @Test
    fun switchTab_clearsStack() {
        val back = BackStack()
        back.push(Screen.Search)
        back.push(Screen.People)
        back.switchTab(Screen.Albums)
        assertEquals(listOf(Screen.Albums), back.stack)
    }

    @Test
    fun savedStateRestoresStack() {
        val back = BackStack(listOf(Screen.Albums, Screen.Search))
        val saved = with(BackStack.Saver) {
            object : androidx.compose.runtime.saveable.SaverScope {
                override fun canBeSaved(value: Any) = true
            }.save(back)!!
        }
        assertEquals(back.stack, BackStack.Saver.restore(saved)!!.stack)
    }
}
