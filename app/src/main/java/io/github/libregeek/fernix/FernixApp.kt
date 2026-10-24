// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import io.github.libregeek.fernix.data.db.PersonArtDb
import io.github.libregeek.fernix.ui.art.AlbumArtFetcher
import io.github.libregeek.fernix.ui.art.AlbumThumbnailFetcher
import io.github.libregeek.fernix.ui.art.AlbumThumbnailKeyer
import io.github.libregeek.fernix.ui.art.TrackThumbnailFetcher
import io.github.libregeek.fernix.ui.art.TrackThumbnailKeyer
import io.github.libregeek.fernix.ui.art.AlbumArtKeyer
import io.github.libregeek.fernix.ui.art.PersonArtFetcher
import io.github.libregeek.fernix.ui.art.PersonArtKeyer
import io.github.libregeek.fernix.ui.art.TrackArtFetcher
import io.github.libregeek.fernix.ui.art.contentResolverOpener
import io.github.libregeek.fernix.ui.art.TrackArtKeyer

class FernixApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * Album art only (spec §2, §7): fetchers for [io.github.libregeek.fernix.ui.art.TrackArt] and album art,
     * no network module, no disk cache (nothing is written to disk), and a small memory cache,
     * since a music app shows few images at once.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                val open = contentResolverOpener(contentResolver)
                val albumTracks: suspend (Long, Int) -> List<Long> = { album, limit ->
                    container.database.trackDao().albumMediaStoreIds(album, limit)
                }
                add(TrackArtKeyer())
                add(TrackArtFetcher.Factory(open))
                add(PersonArtKeyer())
                add(PersonArtFetcher.Factory(PersonArtDb(container.database), open))
                add(AlbumThumbnailKeyer())
                add(AlbumThumbnailFetcher.Factory(container.thumbnails, albumTracks, open))
                add(TrackThumbnailKeyer())
                add(TrackThumbnailFetcher.Factory(container.thumbnails, open))
                add(AlbumArtKeyer())
                add(AlbumArtFetcher.Factory(albumTracks, open))
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.05).build() }
            .diskCache(null)
            .build()
}
