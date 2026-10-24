// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Reports each track that plays through to its end (spec/architecture.md §5 *What counts as a
 * play*, §8). A track counts when ExoPlayer moves on from it by itself, when repeat-one restarts
 * it, or when the queue ends on it. Skipping, seeking to another item, stopping, replacing the
 * queue and pausing never count. It only listens to player events and never touches the audio.
 */
class PlayRecorder(private val onPlayed: (trackId: Long) -> Unit) : Player.Listener {

    /** The `tracks.id` of the item playing now, or null when idle or the item isn't ours. */
    private var current: Long? = null

    private var player: Player? = null

    /** Call once with the player, before playback, so the item already loaded is known. */
    fun attach(player: Player) {
        this.player = player
        current = player.currentMediaItem?.let(::trackIdOf)
        player.addListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // ExoPlayer also reports REPEAT when a seek lands on the end of an item with repeat off, just
        // before the AUTO transition; only count it when repeat-one really restarts the item.
        val repeated = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player?.repeatMode == Player.REPEAT_MODE_ONE
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || repeated) current?.let(onPlayed)
        current = mediaItem?.let(::trackIdOf)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) current?.let(onPlayed)
    }

    private fun trackIdOf(item: MediaItem): Long? = MediaItems.trackId(item.mediaId)
}
