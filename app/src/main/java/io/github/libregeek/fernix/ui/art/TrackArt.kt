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
import okio.Path.Companion.toOkioPath

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

/** The cover of the file with [mediaStoreId], or null if it has none or can't be opened. */
internal fun coverOf(open: (Long) -> ParcelFileDescriptor?, mediaStoreId: Long): Picture? {
    val pfd = open(mediaStoreId) ?: return null
    return TagReader.readPictures(pfd)?.let(Picture::cover)
}

internal fun result(picture: Picture) = SourceFetchResult(
    source = ImageSource(Buffer().write(picture.data), FileSystem.SYSTEM),
    mimeType = picture.mimeType.ifEmpty { null },
    dataSource = DataSource.DISK,
)

/** Opens the read-only descriptor of a track's file through its MediaStore content URI. */
fun contentResolverOpener(resolver: ContentResolver): (Long) -> ParcelFileDescriptor? = { id ->
    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    try {
        resolver.openFileDescriptor(uri, "r")
    } catch (e: Exception) {
        null
    }
}

/**
 * Reads the picture out of the file through the TagLib bridge, on Coil's fetch dispatcher. Returns
 * null (so Coil reports an error, and the UI keeps its placeholder) when the file has no picture or
 * can't be read. [open] gives the file's read-only descriptor, or null if it can't be opened.
 */
class TrackArtFetcher(
    private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    private val data: TrackArt,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = coverOf(open, data.mediaStoreId)?.let(::result)

    class Factory(private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?) : Fetcher.Factory<TrackArt> {

        constructor(resolver: ContentResolver) : this(contentResolverOpener(resolver))

        override fun create(data: TrackArt, options: Options, imageLoader: ImageLoader): Fetcher =
            TrackArtFetcher(open, data)
    }
}

/**
 * Coil model for an album's art: the cover of the first of its tracks (by disc and track number)
 * that has a picture (spec §9, *Album details*). At most [MAX_TRACKS] files are tried.
 */
data class AlbumArt(val albumId: Long) {
    companion object {
        const val MAX_TRACKS = 20
    }
}

class AlbumArtKeyer : Keyer<AlbumArt> {
    override fun key(data: AlbumArt, options: Options): String = "album-art:${data.albumId}"
}

class AlbumArtFetcher(
    private val tracks: suspend (albumId: Long, limit: Int) -> List<Long>,
    private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    private val data: AlbumArt,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        for (id in tracks(data.albumId, AlbumArt.MAX_TRACKS)) {
            coverOf(open, id)?.let { return result(it) }
        }
        return null
    }

    class Factory(
        private val tracks: suspend (albumId: Long, limit: Int) -> List<Long>,
        private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    ) : Fetcher.Factory<AlbumArt> {
        override fun create(data: AlbumArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AlbumArtFetcher(tracks, open, data)
    }
}

/** The thumbnail of an album's art for list rows (spec/architecture.md §7 *Thumbnails*). */
data class AlbumThumbnail(val albumId: Long)

/** The thumbnail of one track's art, for a row whose track has no album. */
data class TrackThumbnail(val mediaStoreId: Long)

class AlbumThumbnailKeyer : Keyer<AlbumThumbnail> {
    override fun key(data: AlbumThumbnail, options: Options): String = "album-thumb:${data.albumId}"
}

class TrackThumbnailKeyer : Keyer<TrackThumbnail> {
    override fun key(data: TrackThumbnail, options: Options): String = "track-thumb:${data.mediaStoreId}"
}

private fun thumbnailResult(file: java.io.File) = SourceFetchResult(
    source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
    mimeType = "image/jpeg",
    dataSource = DataSource.DISK,
)

/**
 * Serves a thumbnail from the [cache] when it has one; otherwise finds the picture ([cover]), makes the
 * small JPEG, stores it and serves that. Art that doesn't exist is remembered, so it isn't looked for again.
 */
private suspend fun thumbnail(cache: ThumbnailCache, key: String, cover: suspend () -> Picture?): FetchResult? {
    cache.get(key)?.let { return thumbnailResult(it) }
    if (cache.hasNone(key)) return null
    val jpeg = cover()?.let { Thumbnails.encode(it.data) }
    if (jpeg == null) {
        cache.putNone(key)
        return null
    }
    return thumbnailResult(cache.put(key, jpeg))
}

class AlbumThumbnailFetcher(
    private val cache: ThumbnailCache,
    private val tracks: suspend (albumId: Long, limit: Int) -> List<Long>,
    private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    private val data: AlbumThumbnail,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = thumbnail(cache, "album-${data.albumId}") {
        tracks(data.albumId, AlbumArt.MAX_TRACKS).firstNotNullOfOrNull { coverOf(open, it) }
    }

    class Factory(
        private val cache: ThumbnailCache,
        private val tracks: suspend (albumId: Long, limit: Int) -> List<Long>,
        private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    ) : Fetcher.Factory<AlbumThumbnail> {
        override fun create(data: AlbumThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            AlbumThumbnailFetcher(cache, tracks, open, data)
    }
}

class TrackThumbnailFetcher(
    private val cache: ThumbnailCache,
    private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    private val data: TrackThumbnail,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = thumbnail(cache, "track-${data.mediaStoreId}") { coverOf(open, data.mediaStoreId) }

    class Factory(
        private val cache: ThumbnailCache,
        private val open: (mediaStoreId: Long) -> ParcelFileDescriptor?,
    ) : Fetcher.Factory<TrackThumbnail> {
        override fun create(data: TrackThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            TrackThumbnailFetcher(cache, open, data)
    }
}
