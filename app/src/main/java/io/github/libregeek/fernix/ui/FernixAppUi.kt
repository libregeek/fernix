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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libregeek.fernix.AppContainer
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.ui.albums.AlbumDetailScreen
import io.github.libregeek.fernix.ui.albums.AlbumsScreen
import io.github.libregeek.fernix.ui.folders.FoldersScreen
import io.github.libregeek.fernix.ui.nowplaying.NowPlayingScreen
import io.github.libregeek.fernix.ui.queue.QueueScreen
import io.github.libregeek.fernix.ui.scan.LibraryFoldersScreen
import io.github.libregeek.fernix.ui.settings.SettingsScreen
import io.github.libregeek.fernix.ui.trackdetails.TrackDetailScreen
import io.github.libregeek.fernix.ui.people.PeopleScreen
import io.github.libregeek.fernix.ui.people.PersonDetailScreen
import io.github.libregeek.fernix.ui.tracks.TracksScreen
import io.github.libregeek.fernix.data.scan.ScanState
import io.github.libregeek.fernix.playback.PlaybackUiState
import io.github.libregeek.fernix.ui.nav.BackStack
import io.github.libregeek.fernix.ui.nav.Screen

@get:StringRes
private val Screen.label: Int
    get() = when (this) {
        Screen.Folders -> R.string.tab_folders
        Screen.Tracks -> R.string.tab_tracks
        Screen.Albums -> R.string.tab_albums
        Screen.Playlists -> R.string.tab_playlists
        Screen.People -> R.string.tab_people
        Screen.NowPlaying -> R.string.now_playing
        Screen.Queue -> R.string.queue
        Screen.TrackDetail -> R.string.track_details
        Screen.Settings -> R.string.settings
        Screen.LibraryFolders -> R.string.settings_library_folders
        Screen.AlbumDetail -> R.string.tab_albums
        Screen.PersonDetail -> R.string.tab_people
        Screen.PlaylistDetail -> R.string.tab_playlists
    }

@get:DrawableRes
private val Screen.icon: Int
    get() = when (this) {
        Screen.Folders -> R.drawable.ic_folder
        Screen.Tracks -> R.drawable.ic_music_note
        Screen.Albums -> R.drawable.ic_album
        Screen.Playlists -> R.drawable.ic_playlist
        Screen.People -> R.drawable.ic_people
        Screen.NowPlaying -> R.drawable.ic_play
        Screen.Queue -> R.drawable.ic_queue_music
        Screen.TrackDetail -> R.drawable.ic_info
        Screen.Settings -> R.drawable.ic_settings
        Screen.LibraryFolders -> R.drawable.ic_folder
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
    val openTrack: (Long) -> Unit = { backStack.push(Screen.TrackDetail, it) }

    BackHandler(enabled = backStack.canPop) { backStack.pop() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (current.isTab) {
                LibraryTopBar(stringResource(current.label), onSettings = { backStack.push(Screen.Settings) })
            }
        },
        bottomBar = {
            Column {
                // A scan runs in the background; its progress shows on every screen, above the mini-player.
                if (scanState is ScanState.Running) ScanProgress(scanState)
                if (current != Screen.NowPlaying) {
                    val miniPlayer: MiniPlayerViewModel = viewModel(
                        factory = viewModelFactory { initializer { MiniPlayerViewModel(container.database, container.playback) } },
                    )
                    val playing by miniPlayer.track.collectAsStateWithLifecycle()
                    MiniPlayer(playback, playing, onOpen = { backStack.push(Screen.NowPlaying) }, onPlayPause = onPlayPause, onNext = onNext)
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
            FoldersScreen(container.database, container.playback, onOpenTrack = openTrack, scanState = scanState, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        if (current == Screen.NowPlaying) {
            NowPlayingScreen(
                container.database,
                container.settings,
                container.playback,
                onBack = { backStack.pop() },
                onOpenPerson = { backStack.push(Screen.Tracks, it) },
                onOpenQueue = { backStack.push(Screen.Queue) },
                onOpenTag = { keyId, valueId -> backStack.push(Screen.Tracks, valueId, keyId) },
                onOpenTrack = openTrack,
                onOpenAlbum = { backStack.push(Screen.AlbumDetail, it) },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.Settings) {
            SettingsScreen(
                container.database,
                container.settings,
                scanning = scanState is ScanState.Running,
                onRescan = onRescan,
                onChangeFolders = { backStack.push(Screen.LibraryFolders) },
                onBack = { backStack.pop() },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.LibraryFolders) {
            LibraryFoldersScreen(
                container.database,
                container.mediaSource,
                container.settings,
                onApply = container::changeScope,
                onDone = { backStack.pop() },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.TrackDetail) {
            TrackDetailScreen(
                backStack.currentArg,
                container.database,
                container.settings,
                container.trackFiles,
                onBack = { backStack.pop() },
                onOpenTag = { keyId, valueId -> backStack.push(Screen.Tracks, valueId, keyId) },
                onOpenPerson = { backStack.push(Screen.PersonDetail, it) },
                onOpenAlbum = { backStack.push(Screen.AlbumDetail, it) },
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
        if (current == Screen.Tracks) {
            TracksScreen(
                container.database,
                container.trackSearch,
                container.playback,
                personId = backStack.currentArg,
                tagKeyId = backStack.currentArg2,
                scanState = scanState,
                onOpenTrack = openTrack,
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
                onOpenTrack = openTrack,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.People) {
            PeopleScreen(
                container.database,
                onOpenPerson = { backStack.push(Screen.PersonDetail, it) },
                scanState = scanState,
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
                onOpenTrack = openTrack,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        if (current == Screen.Albums) {
            AlbumsScreen(
                container.database,
                container.playback,
                onOpenAlbum = { backStack.push(Screen.AlbumDetail, it) },
                scanState = scanState,
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
                onOpenTrack = openTrack,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
    }
}

/** Title and settings gear of the five library screens; the gear opens Settings directly (spec §9). */
@Composable
private fun LibraryTopBar(title: String, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(48.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onSettings) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
            }
        }
    }
}

/** An indeterminate bar with the number of files processed so far (the total isn't known up front). */
@Composable
private fun ScanProgress(state: ScanState.Running) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                state.describe(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ScanState.describe(): String = when (this) {
    ScanState.Idle -> stringResource(R.string.scan_idle)
    is ScanState.Running -> pluralStringResource(R.plurals.scan_running, processed, processed)
    is ScanState.Finished -> pluralStringResource(R.plurals.scan_finished, processed, processed)
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
