// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PlaylistEntity
import io.github.libregeek.fernix.data.db.PlaylistRow
import io.github.libregeek.fernix.data.db.TrackRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The **Add to playlist** bottom sheet (spec/architecture.md §9.6): a **New playlist** row that opens
 * a name field in place, then Favourites and the user's playlists as tiles with their track counts.
 * Choosing one appends the tracks from [tracks] (resolved only then, so a folder or album isn't loaded
 * just to open the sheet; ones already in the playlist are skipped), says so, and closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(db: FernixDatabase, tracks: suspend () -> List<TrackRow>, onDone: () -> Unit) {
    var playlists by remember { mutableStateOf<List<PlaylistRow>?>(null) }
    val favouritesId by produceState<Long?>(null) { value = db.playlistDao().favouritesId() }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val favouritesName = stringResource(R.string.playlist_favourites)

    LaunchedEffect(Unit) {
        // Favourites first, then the user's own playlists; the read-only defaults are never offered.
        val dao = db.playlistDao()
        playlists = listOfNotNull(dao.favouritesSummary().first()) + dao.summaries().first()
    }

    fun addTo(playlistId: Long, playlistName: String) {
        scope.launch {
            val rows = tracks()
            withContext(Dispatchers.IO) {
                db.playlistDao().append(playlistId, rows.map { it.id }, System.currentTimeMillis())
            }
            Toast.makeText(context, resources.getString(R.string.added_to_playlist, playlistName), Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    fun create() {
        val title = name.trim()
        if (title.isEmpty()) return
        scope.launch {
            val now = System.currentTimeMillis()
            val id = withContext(Dispatchers.IO) {
                db.playlistDao().insert(PlaylistEntity(name = title, createdAt = now, updatedAt = now))
            }
            addTo(id, title)
        }
    }

    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.add_to_playlist),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            if (creating) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The keyboard opens straight away: the name is the only thing left to do.
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        label = { Text(stringResource(R.string.playlist_name)) },
                        singleLine = true,
                    )
                    Button(onClick = ::create, enabled = name.isNotBlank()) { Text(stringResource(R.string.playlist_create)) }
                }
            } else {
                PlaylistTile(
                    icon = R.drawable.ic_add,
                    highlighted = true,
                    title = stringResource(R.string.playlist_new),
                    subtitle = null,
                    onClick = { creating = true },
                )
            }
            LazyColumn {
                items(playlists.orEmpty(), key = { it.id }) { playlist ->
                    val isFavourites = playlist.id == favouritesId
                    val title = if (isFavourites) favouritesName else playlist.name
                    PlaylistTile(
                        icon = if (isFavourites) R.drawable.ic_favorite else R.drawable.ic_playlist,
                        highlighted = false,
                        title = title,
                        subtitle = pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount),
                        onClick = { addTo(playlist.id, title) },
                    )
                }
            }
        }
    }
}

/** A row of the sheet: a rounded tonal tile with the icon, the name and, when given, a smaller line under it. */
@Composable
private fun PlaylistTile(icon: Int, highlighted: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
