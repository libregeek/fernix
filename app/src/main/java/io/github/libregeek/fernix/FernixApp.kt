// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import io.github.libregeek.fernix.ui.art.TrackArtFetcher
import io.github.libregeek.fernix.ui.art.TrackArtKeyer

class FernixApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * Album art only (spec §2, §7): one fetcher for [io.github.libregeek.fernix.ui.art.TrackArt],
     * no network module, no disk cache (nothing is written to disk), and a small memory cache,
     * since a music app shows few images at once.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(TrackArtKeyer())
                add(TrackArtFetcher.Factory(contentResolver))
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.05).build() }
            .diskCache(null)
            .build()
}
