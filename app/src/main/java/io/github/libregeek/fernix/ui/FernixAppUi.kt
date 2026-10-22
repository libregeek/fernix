// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.ui.nav.BackStack
import io.github.libregeek.fernix.ui.nav.Screen

@get:StringRes
private val Screen.label: Int
    get() = when (this) {
        Screen.Folders -> R.string.tab_folders
        Screen.Search -> R.string.tab_search
        Screen.Albums -> R.string.tab_albums
        Screen.Playlists -> R.string.tab_playlists
        Screen.People -> R.string.tab_people
    }

@get:DrawableRes
private val Screen.icon: Int
    get() = when (this) {
        Screen.Folders -> R.drawable.ic_folder
        Screen.Search -> R.drawable.ic_search
        Screen.Albums -> R.drawable.ic_album
        Screen.Playlists -> R.drawable.ic_playlist
        Screen.People -> R.drawable.ic_people
    }

/** App shell: bottom navigation for the five library screens plus the back stack. */
@Composable
fun FernixAppUi() {
    val backStack = rememberSaveable(saver = BackStack.Saver) { BackStack() }
    val current = backStack.current

    BackHandler(enabled = backStack.canPop) { backStack.pop() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Screen.entries.filter { it.isTab }.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == backStack.stack.first(),
                        onClick = { backStack.switchTab(tab) },
                        icon = {
                            Icon(painterResource(tab.icon), contentDescription = stringResource(tab.label))
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            // Placeholder until each screen is built (Phase 5).
            Text(stringResource(current.label), style = MaterialTheme.typography.titleLarge)
        }
    }
}
