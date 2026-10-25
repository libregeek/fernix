// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.tags.TagValues

/**
 * Queue items (spec/architecture.md §8): `mediaId` is `tracks.id`, so the playing item maps back
 * to its row; the rest is only what the notification shows. Everything else is read from the
 * database when needed.
 */
object MediaItems {

    fun mediaId(trackId: Long): String = trackId.toString()

    /** The `tracks.id` of a queue item's `mediaId`, or null if it isn't one of ours. */
    fun trackId(mediaId: String): Long? = mediaId.toLongOrNull()

    /** Extras key holding the plain artist once the lock-screen line has replaced `artist` in the metadata. */
    const val EXTRA_ARTIST = "fernix.artist"

    /** The artist of [metadata] as the lists show it, not the lock-screen line. */
    fun artistOf(metadata: MediaMetadata): String =
        (metadata.extras?.getString(EXTRA_ARTIST) ?: metadata.artist?.toString()).orEmpty()

    /** [item] with the lock-screen line as its `artist`: the notification and lock screen show title and artist only. */
    fun withLockScreenLine(item: MediaItem, line: String?): MediaItem {
        val metadata = item.mediaMetadata
        val extras = Bundle(metadata.extras ?: Bundle.EMPTY).apply { putString(EXTRA_ARTIST, artistOf(metadata)) }
        return item.buildUpon()
            .setMediaMetadata(metadata.buildUpon().setArtist(line).setExtras(extras).build())
            .build()
    }

    fun of(track: TrackRow): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId(track.id))
        .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.mediaStoreId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(display(track.artist))
                .setAlbumTitle(track.album.let(::display))
                .build(),
        )
        .build()

    /** Multi-value columns are shown as `"A; B"`. */
    internal fun display(joined: String): String? =
        TagValues.split(joined).joinToString("; ").ifEmpty { null }
}
