// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.libregeek.fernix.data.db.TrackDao
import io.github.libregeek.fernix.tags.Credits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts the album and the people of the playing track on the lock screen and in the notification
 * (spec/architecture.md §8): the title stays the title, and the second line becomes
 * `Album · composers; lyricists; artists`. Queue items carry only the artist (§8), so when an item
 * becomes current its row is read and the item is replaced by the same item with the new line. The
 * source and the position are untouched, so playback carries on without a gap.
 *
 * Main thread only, like the player.
 */
class LockScreenCredits(private val player: Player, private val tracks: TrackDao) : Player.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun attach() {
        player.addListener(this)
        player.currentMediaItem?.let(::enrich)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItem?.let(::enrich)
    }

    private fun enrich(item: MediaItem) {
        if (item.mediaMetadata.extras?.containsKey(MediaItems.EXTRA_ARTIST) == true) return
        val trackId = MediaItems.trackId(item.mediaId) ?: return
        scope.launch {
            val line = withContext(Dispatchers.IO) {
                tracks.byId(trackId)?.let { Credits.lockScreenLine(it.album, it.composer, it.lyricist, it.artist) }
            }
            // The queue may have moved on while the row was read.
            val index = player.currentMediaItemIndex
            if (player.currentMediaItem?.mediaId != item.mediaId) return@launch
            player.replaceMediaItem(index, MediaItems.withLockScreenLine(player.currentMediaItem ?: item, line))
        }
    }
}
