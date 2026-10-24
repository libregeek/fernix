// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.trackdetails

import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import io.github.libregeek.fernix.tags.TagValues
import io.github.libregeek.fernix.playback.MediaItems
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.tags.TagSplitter
import io.github.libregeek.fernix.ui.nowplaying.TagValue
import io.github.libregeek.fernix.tags.AudioProperties
import io.github.libregeek.fernix.ui.art.TrackArt
import io.github.libregeek.fernix.ui.nowplaying.TagLine
import java.util.Date

/**
 * Track details (spec §9): cover, every tag, technical details and file details. People values open
 * their person details and the album name opens the album; other values aren't tappable yet (they
 * will open a tag filter in Search).
 */
@Composable
fun TrackDetailScreen(
    trackId: Long,
    db: FernixDatabase,
    settings: Settings,
    openFile: (Long) -> android.os.ParcelFileDescriptor?,
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenTag: (keyId: Long, valueId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: TrackDetailViewModel = viewModel(
        key = "track$trackId",
        factory = viewModelFactory { initializer { TrackDetailViewModel(trackId, db, settings, openFile) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val track = state.track
    LaunchedEffect(Unit) { vm.reloadDelimiters() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
        }
        if (track == null) {
            if (!state.loading) {
                Text(stringResource(R.string.details_missing), modifier = Modifier.padding(24.dp))
            }
            return@Column
        }

        Box(
            Modifier
                .padding(horizontal = 80.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_album),
                null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AsyncImage(
                model = TrackArt(track.mediaStoreId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                track.title.ifEmpty { track.displayName },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            AlbumLine(track.album, track.date, onClick = track.albumId?.let { id -> { onOpenAlbum(id) } })
        }

        // Composer, Lyricist and Artists lead; everything else follows under Other tags. A value is plain
        // text in the accent colour: a person's opens their details, any other value opens Tracks filtered by it.
        val byKey = state.tags.associateBy { it.label }
        val people = listOf(
            "COMPOSER" to R.string.details_composer,
            "LYRICIST" to R.string.details_lyricist,
            "ARTIST" to R.string.details_artists,
        )
        val shown = setOf("TITLE", "ALBUM", "DATE") + people.map { it.first }
        val splitter = { line: TagLine, value: TagValue -> vm.askSplit(line.label, value) }
        val lead = people.mapNotNull { (key, label) -> byKey[key]?.let { it to label } }
        if (lead.isNotEmpty()) {
            Section(null) {
                lead.forEach { (line, label) ->
                    TagRow(stringResource(label), line, state.delimiters, onOpenPerson, onOpenTag, splitter)
                }
            }
        }
        val other = state.tags.filter { it.label !in shown }
        if (other.isNotEmpty()) {
            Section(stringResource(R.string.details_other_tags)) {
                other.forEach { line -> TagRow(line.label, line, state.delimiters, onOpenPerson, onOpenTag, splitter) }
            }
        }

        state.pendingSplit?.let { split ->
            AlertDialog(
                onDismissRequest = { if (!state.splitting) vm.cancelSplit() },
                title = { Text(stringResource(R.string.split_value)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${split.key}: ${split.value}")
                        Text(
                            "\u2192 " + split.parts.joinToString(" | "),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            pluralStringResource(R.plurals.split_applies_to, split.libraryTracks, split.libraryTracks),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = vm::confirmSplit, enabled = !state.splitting) { Text(stringResource(R.string.split_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = vm::cancelSplit, enabled = !state.splitting) { Text(stringResource(R.string.cancel)) }
                },
            )
        }

        state.audio?.let { audio ->
            Section(stringResource(R.string.details_technical)) { Technical(audio, state.format) }
        }

        state.file?.let { file ->
            val context = LocalContext.current
            Section(stringResource(R.string.details_file)) {
                Fact(stringResource(R.string.details_name), file.name)
                if (file.folder.isNotEmpty()) Fact(stringResource(R.string.details_folder), file.folder)
                Fact(stringResource(R.string.details_volume), volumeName(file.volume))
                Fact(stringResource(R.string.details_size), Formatter.formatFileSize(context, file.sizeBytes))
                Fact(
                    stringResource(R.string.details_modified),
                    DateFormat.getMediumDateFormat(context).format(Date(file.modifiedSeconds * 1000)) + " " +
                        DateFormat.getTimeFormat(context).format(Date(file.modifiedSeconds * 1000)),
                )
                Fact(stringResource(R.string.details_uri), file.contentUri)
            }
        }
    }
}

@Composable
private fun volumeName(volume: String): String =
    if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) stringResource(R.string.volume_internal) else volume

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        content()
    }
}

/** The album in bold, smaller than the title, with its date in brackets after it; the name opens the album. */
@Composable
private fun AlbumLine(album: String, date: String, onClick: (() -> Unit)?) {
    val name = MediaItems.display(album)
    val first = TagValues.split(date).firstOrNull()
    if (name == null && first == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        if (name != null) {
            Text(
                name,
                modifier = if (onClick != null) Modifier.clickable(onClick = onClick).weight(1f, fill = false) else Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (first != null) {
            Text(
                (if (name != null) " " else "") + "($first)",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One tag as a compact row: its name, then its values side by side (they wrap). A value is accent-coloured
 * text: a person's opens their details, any other value opens Tracks with it as a filter. A value holding
 * one of the Settings delimiters has a small split button after it (TITLE, ALBUM and DATE never do).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagRow(
    label: String,
    line: TagLine,
    delimiters: List<String>,
    onOpenPerson: (Long) -> Unit,
    onOpenTag: (keyId: Long, valueId: Long) -> Unit,
    onSplit: (TagLine, TagValue) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(92.dp).padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            line.values.forEach { value ->
                val splittable = line.label !in TagSplitter.UNSPLITTABLE_KEYS && TagSplitter.contains(value.text, delimiters)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        value.text,
                        modifier = Modifier.clickable {
                            if (value.isPerson) onOpenPerson(value.valueId) else onOpenTag(value.keyId, value.valueId)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (splittable) {
                        IconButton(onClick = { onSplit(line, value) }, modifier = Modifier.size(28.dp)) {
                            Icon(painterResource(R.drawable.ic_call_split), stringResource(R.string.split_value), Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Only what the format reports: a zero field is hidden. */
@Composable
private fun Technical(audio: AudioProperties, format: String?) {
    format?.let { Fact(stringResource(R.string.details_format), it) }
    if (audio.lengthMs > 0) Fact(stringResource(R.string.details_duration), clock(audio.lengthMs.toLong()))
    if (audio.bitrateKbps > 0) Fact(stringResource(R.string.details_bitrate), "${audio.bitrateKbps} kbps")
    if (audio.sampleRateHz > 0) Fact(stringResource(R.string.details_sample_rate), "%.1f kHz".format(audio.sampleRateHz / 1000f))
    if (audio.channels > 0) {
        Fact(
            stringResource(R.string.details_channels),
            when (audio.channels) {
                1 -> stringResource(R.string.details_channels_mono)
                2 -> stringResource(R.string.details_channels_stereo)
                else -> pluralStringResource(R.plurals.details_channels_n, audio.channels, audio.channels)
            },
        )
    }
    if (audio.bitsPerSample > 0) Fact(stringResource(R.string.details_bits), "${audio.bitsPerSample} bit")
}

@Composable
private fun Fact(label: String, value: String) {
    // Laid out like a tag row, so the whole page reads as one compact list.
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(92.dp).padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
