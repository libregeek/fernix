// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * A file that cannot be played (gone, unreadable, damaged, access revoked) must not leave the player
 * stuck: it moves on to the next item and keeps playing. If every item in the queue has failed in a row
 * (e.g. the storage went away, or repeat-all would loop forever) it stops where it is.
 */
class SkipOnError(
    private val player: Player,
    /** Told which item failed and why, before the player moves on (to log it). */
    private val onFailed: (mediaId: String?, reason: String) -> Unit = { _, _ -> },
) : Player.Listener {

    private var failures = 0

    fun attach() = player.addListener(this)

    override fun onPlayerError(error: PlaybackException) {
        failures++
        onFailed(player.currentMediaItem?.mediaId, error.errorCodeName)
        if (failures < player.mediaItemCount && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) failures = 0
    }
}
