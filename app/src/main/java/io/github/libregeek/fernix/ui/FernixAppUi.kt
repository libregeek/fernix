// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import io.github.libregeek.fernix.ui.playlists.PlaylistDetailScreen
import io.github.libregeek.fernix.ui.playlists.PlaylistsScreen
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
import io.github.libregeek.fernix.AppContainer
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.ui.albums.AlbumDetailScreen
import io.github.libregeek.fernix.ui.albums.AlbumsScreen
import io.github.libregeek.fernix.ui.folders.FoldersScreen
import io.github.libregeek.fernix.ui.nowplaying.NowPlayingScreen
import io.github.libregeek.fernix.ui.queue.QueueScreen
import io.github.libregeek.fernix.ui.people.PeopleScreen
import io.github.libregeek.fernix.ui.people.PersonDetailScreen
import io.github.libregeek.fernix.ui.search.SearchScreen
import io.github.libregeek.fernix.data.scan.ScanState
import io.github.libregeek.fernix.playback.PlaybackUiState
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
        Screen.NowPlaying -> R.string.now_playing
        Screen.Queue -> R.string.queue
        Screen.AlbumDetail -> R.string.tab_albums
        Screen.PersonDetail -> R.string.tab_people
        Screen.PlaylistDetail -> R.string.tab_playlists
    }

@get:DrawableRes
private val Screen.icon: Int
    get() = when (this) {
        Screen.Folders -> R.drawable.ic_folder
        Screen.Search -> R.drawable.ic_search
        Screen.Albums -> R.drawable.ic_album
        Screen.Playlists -> R.drawable.ic_playlist
        Screen.People -> R.drawable.ic_people
        Screen.NowPlaying -> R.drawable.ic_play
        Screen.Queue -> R.drawable.ic_queue_music
        Screen.AlbumDetail -> R.drawable.ic_album
        Screen.PersonDetail -> R.drawable.ic_people
        Screen.PlaylistDetail -> R.drawable.ic_playlist
    }

/** App shell: bottom navigation for the five library screens plus the back stack. */
@Composable
fun FernixAppUi(
    container: AppContainer,
    scanState: ScanState,
    onRescan: () -> Unit,
    playback: PlaybackUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val backStack = rememberSaveable(saver = BackStack.Saver) { BackStack() }
    val current = backStack.current

    BackHandler(enabled = backStack.canPop) { backStack.pop() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column {
                if (current != Screen.NowPlaying) {
                    MiniPlayer(playback, onOpen = { backStack.push(Screen.NowPlaying) }, onPlayPause = onPlayPause, onNext = onNext)
                }
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
            }
        },
    ) { padding ->
        if (current == Screen.Folders) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (scanState is ScanState.Running) {
                    Text(
                        scanState.describe(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FoldersScreen(container.database, container.playback)
            }
            return@Scaffold
        }
        if (current == Screen.NowPlaying) {
            NowPlayingScreen(
                container.database,
                container.settings,
                container.playback,
                onBack = { backStack.pop() },
                onOpenPerson = { backStack.push(Screen.Search, it) },
                onOpenQueue = { backStack.push(Screen.Queue) },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.Queue) {
            QueueScreen(
                container.playback,
                onBack = { backStack.pop() },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.Search) {
            SearchScreen(
                container.database,
                container.trackSearch,
                container.playback,
                personId = backStack.currentArg,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.Playlists) {
            PlaylistsScreen(
                container.database,
                container.trackSearch,
                container.playback,
                onOpenPlaylist = { backStack.push(Screen.PlaylistDetail, it) },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.PlaylistDetail) {
            PlaylistDetailScreen(
                backStack.currentArg,
                container.database,
                container.trackSearch,
                container.playback,
                onBack = { backStack.pop() },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.People) {
            PeopleScreen(
                container.database,
                onOpenPerson = { backStack.push(Screen.PersonDetail, it) },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.PersonDetail) {
            val personId = backStack.currentArg
            PersonDetailScreen(
                personId,
                container.database,
                container.trackSearch,
                container.playback,
                onBack = { backStack.pop() },
                onShowInSearch = { backStack.push(Screen.Search, personId) },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.Albums) {
            AlbumsScreen(
                container.database,
                container.playback,
                onOpenAlbum = { backStack.push(Screen.AlbumDetail, it) },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.AlbumDetail) {
            AlbumDetailScreen(
                backStack.currentArg,
                container.database,
                container.playback,
                onBack = { backStack.pop() },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
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
