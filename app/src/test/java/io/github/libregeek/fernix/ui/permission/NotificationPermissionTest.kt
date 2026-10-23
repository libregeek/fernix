// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {

    private fun ask(sdk: Int = 33, granted: Boolean = false, asked: Boolean = false, playing: Boolean = true) =
        NotificationPermission.shouldRequest(sdk, granted, asked, playing)

    @Test
    fun asksOnFirstPlayback_onAndroid13() = assertTrue(ask())

    @Test
    fun neverBeforeAndroid13() = assertFalse(ask(sdk = 32))

    @Test
    fun notWhileNothingPlays() = assertFalse(ask(playing = false))

    @Test
    fun notWhenGranted() = assertFalse(ask(granted = true))

    @Test
    fun onlyOnce() = assertFalse(ask(asked = true))
}
