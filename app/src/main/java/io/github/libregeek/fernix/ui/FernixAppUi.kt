// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.scan.ScanState
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
fun FernixAppUi(scanState: ScanState, onRescan: () -> Unit) {
    val backStack = rememberSaveable(saver = BackStack.Saver) { BackStack() }
    val current = backStack.current

    BackHandler(enabled = backStack.canPop) { backStack.pop() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            CompactNavBar {
                Screen.entries.filter { it.isTab }.forEach { tab ->
                    CompactNavItem(
                        selected = tab == backStack.stack.first(),
                        onClick = { backStack.switchTab(tab) },
                        icon = tab.icon,
                        description = stringResource(tab.label),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            // Placeholder until each screen is built (Phase 5); the scan status and Rescan button
            // move to the library top bar and Settings (Phases 5 and 8).
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(current.label), style = MaterialTheme.typography.titleLarge)
                Text(scanState.describe(), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRescan, enabled = scanState !is ScanState.Running) {
                    Text(stringResource(R.string.rescan))
                }
            }
        }
    }
}

@Composable
private fun ScanState.describe(): String = when (this) {
    ScanState.Idle -> stringResource(R.string.scan_idle)
    is ScanState.Running -> stringResource(R.string.scan_running, processed)
    is ScanState.Finished -> stringResource(R.string.scan_finished, processed)
    is ScanState.Failed -> stringResource(R.string.scan_failed, message)
}

/** Icon-only bottom bar, shorter than Material 3's 80 dp NavigationBar (which is sized for labels). */
@Composable
private fun CompactNavBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(BAR_HEIGHT),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun RowScope.CompactNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    description: String,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(BAR_HEIGHT)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(INDICATOR_WIDTH)
                .height(INDICATOR_HEIGHT)
                .clip(CircleShape)
                .then(
                    if (selected) {
                        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                modifier = Modifier.size(24.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private val BAR_HEIGHT = 56.dp
private val INDICATOR_WIDTH = 64.dp
private val INDICATOR_HEIGHT = 32.dp
