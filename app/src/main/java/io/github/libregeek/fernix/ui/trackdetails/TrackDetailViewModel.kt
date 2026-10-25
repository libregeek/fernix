// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.trackdetails

import android.content.ContentUris
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.SplitCandidate
import io.github.libregeek.fernix.data.db.TagSplitService
import io.github.libregeek.fernix.data.db.PlayingTag
import io.github.libregeek.fernix.data.db.TrackEntity
import io.github.libregeek.fernix.data.scan.ScanScope
import io.github.libregeek.fernix.tags.AudioProperties
import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.tags.TagReader
import io.github.libregeek.fernix.ui.nowplaying.TagLine
import io.github.libregeek.fernix.ui.nowplaying.TagValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File details from the `tracks` and `folders` rows (spec/architecture.md §9). */
data class FileInfo(
    val name: String,
    val folder: String,
    val volume: String,
    val sizeBytes: Long,
    /** Epoch seconds, as MediaStore reports it. */
    val modifiedSeconds: Long,
    val contentUri: String,
)

data class TrackDetailState(
    val loading: Boolean = true,
    /** Null once loaded means the track no longer exists (e.g. removed by a rescan). */
    val track: TrackEntity? = null,
    val file: FileInfo? = null,
    /** "MP3", "FLAC", ...: from the file extension, since TagLib's properties don't name the codec. */
    val format: String? = null,
    /** Null until read from the file, and when it can't be read. */
    val audio: AudioProperties? = null,
    /** Every tag, each key with all its values in stored order. */
    val tags: List<TagLine> = emptyList(),
    /** The COMMENT values read from the file in full (the database keeps none over 512 characters); empty when unread. */
    val comments: List<String> = emptyList(),
    /** The delimiters from Settings; a value holding one gets a split button. */
    val delimiters: List<String> = emptyList(),
    /** A split waiting for the user's confirmation, or being applied. */
    val pendingSplit: SplitCandidate? = null,
    val splitting: Boolean = false,
)

/**
 * Track details (spec §9): the tags and file rows from the database, the technical details read
 * from the file on demand through its descriptor (never stored).
 */
class TrackDetailViewModel(
    private val trackId: Long,
    private val db: FernixDatabase,
    private val settings: Settings,
    private val openFile: (mediaStoreId: Long) -> ParcelFileDescriptor?,
) : ViewModel() {

    private val splitService = TagSplitService(db)

    private val _state = MutableStateFlow(TrackDetailState(delimiters = settings.splitDelimiters))
    val state: StateFlow<TrackDetailState> = _state

    /** Settings may have changed the delimiters since this screen was last shown. */
    fun reloadDelimiters() = _state.update { it.copy(delimiters = settings.splitDelimiters) }

    /** The split button of a value: works out what the split would do and asks for confirmation. */
    fun askSplit(key: String, value: TagValue) {
        val delimiters = _state.value.delimiters
        viewModelScope.launch {
            val candidate = withContext(Dispatchers.IO) { splitService.candidateFor(key, value.valueId, value.text, delimiters) }
            _state.update { it.copy(pendingSplit = candidate) }
        }
    }

    fun cancelSplit() = _state.update { it.copy(pendingSplit = null) }

    /** Splits the value in every track that carries it; the tags below update by themselves. */
    fun confirmSplit() {
        val candidate = _state.value.pendingSplit ?: return
        _state.update { it.copy(splitting = true) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { splitService.split(candidate) }
            _state.update { it.copy(pendingSplit = null, splitting = false) }
        }
    }

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val track = db.trackDao().byId(trackId) ?: return@withContext null
                val folder = db.folderDao().byId(track.folderId)?.path.orEmpty()
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.mediaStoreId)
                track to FileInfo(
                    name = track.displayName,
                    folder = folder.substringAfter('/', ""),
                    volume = ScanScope.volumeOf(folder),
                    sizeBytes = track.sizeBytes,
                    modifiedSeconds = track.dateModified,
                    contentUri = uri.toString(),
                )
            }
            if (loaded == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val (track, file) = loaded
            _state.update { it.copy(loading = false, track = track, file = file, format = formatOf(track.displayName)) }
        }
        viewModelScope.launch {
            db.tagDao().allTagsOf(trackId).collect { tags -> _state.update { it.copy(tags = group(tags)) } }
        }
        viewModelScope.launch {
            val track = withContext(Dispatchers.IO) { db.trackDao().byId(trackId) } ?: return@launch
            val audio = withContext(Dispatchers.IO) { openFile(track.mediaStoreId)?.let(TagReader::readAudioProperties) }
            _state.update { it.copy(audio = audio) }
        }
        viewModelScope.launch {
            val track = withContext(Dispatchers.IO) { db.trackDao().byId(trackId) } ?: return@launch
            val comments = withContext(Dispatchers.IO) {
                openFile(track.mediaStoreId)?.let(TagReader::readProperties)
                    ?.filterKeys { it.trim().uppercase() == "COMMENT" }?.values?.flatten().orEmpty()
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            }
            _state.update { it.copy(comments = comments) }
        }
    }

    companion object {
        /** One line per key, in key order, with every value; people keys are marked tappable. */
        fun group(tags: List<PlayingTag>): List<TagLine> = tags.groupBy { it.key }.map { (key, rows) ->
            TagLine(key, rows.map { TagValue(it.valueId, it.value, PeopleTags.isPeopleKey(key), it.keyId) })
        }

        /** The codec as the user knows it, from the file extension; unknown extensions are shown as they are. */
        fun formatOf(displayName: String): String? {
            val extension = displayName.substringAfterLast('.', "").lowercase()
            return when (extension) {
                "" -> null
                "mp3" -> "MP3"
                "flac" -> "FLAC"
                "ogg", "oga" -> "Ogg Vorbis"
                "opus" -> "Opus"
                "wav" -> "WAV"
                else -> extension.uppercase()
            }
        }
    }
}
