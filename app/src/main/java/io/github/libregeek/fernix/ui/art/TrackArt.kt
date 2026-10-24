// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.art

import android.content.ContentResolver
import android.content.ContentUris
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.libregeek.fernix.tags.Picture
import io.github.libregeek.fernix.tags.TagReader
import okio.Buffer
import okio.FileSystem

/**
 * Coil model for the art of one track: its front cover, else its first embedded picture (spec
 * §7 "Pictures", §9). Nothing is stored or written to disk; Coil decodes the bytes into its
 * memory cache.
 */
data class TrackArt(val mediaStoreId: Long)

/** Memory-cache key: the same file is the same image. */
class TrackArtKeyer : Keyer<TrackArt> {
    override fun key(data: TrackArt, options: Options): String = "track-art:${data.mediaStoreId}"
}

/**
 * Reads the picture out of the file through its MediaStore content URI and the TagLib bridge, on
 * Coil's fetch dispatcher. Returns null (so Coil reports an error, and the UI keeps its
 * placeholder) when the file has no picture or can't be read.
 */
class TrackArtFetcher(
    private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    private val data: TrackArt,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val pfd = open(data.mediaStoreId) ?: return null
        val picture = TagReader.readPictures(pfd)?.let(Picture::cover) ?: return null
        return SourceFetchResult(
            source = ImageSource(Buffer().write(picture.data), FileSystem.SYSTEM),
            mimeType = picture.mimeType.ifEmpty { null },
            dataSource = DataSource.DISK,
        )
    }

    /** [open] gives the read-only descriptor of the track's file, or null if it can't be opened. */
    class Factory(private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?) : Fetcher.Factory<TrackArt> {

        constructor(resolver: ContentResolver) : this({ id ->
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            try {
                resolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            }
        })

        override fun create(data: TrackArt, options: Options, imageLoader: ImageLoader): Fetcher =
            TrackArtFetcher(open, data)
    }
}
