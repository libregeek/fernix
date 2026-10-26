// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import androidx.media3.common.Player
import io.github.libregeek.fernix.data.SavedQueueState
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.QueueDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the play queue across restarts of the app and of the device (spec/architecture.md §8 *Saved
 * queue*). The track ids go to the `queue_items` table whenever the queue itself changes (a moment after,
 * so a burst of edits is one write); where the queue was (current item, position, shuffle, repeat) goes to
 * [Settings], which is cheap enough to write on every transition, pause and every few seconds of playback.
 * On start the queue is put back **paused**: the user presses play.
 *
 * Main thread only, like the player; the database work runs on `Dispatchers.IO`.
 */
class SavedQueue(
    private val player: Player,
    private val dao: QueueDao,
    private val settings: Settings,
) : Player.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queueJob: Job? = null

    /** The ids last written to (or read from) the table, so an unchanged queue is not written again. */
    private var savedIds: List<Long>? = null

    /** Restores the saved queue if the player is still empty, then starts keeping it. */
    fun start() {
        scope.launch {
            restore()
            player.addListener(this@SavedQueue)
            while (true) {
                delay(SAVE_EVERY_MS)
                if (player.isPlaying) saveState()
            }
        }
    }

    private suspend fun restore() {
        val saved = settings.savedQueue
        if (saved.size == 0 || player.mediaItemCount > 0) return
        val rows = withContext(Dispatchers.IO) { dao.load() }
        // The user may have started something while the table was read: theirs wins.
        if (rows.isEmpty() || player.mediaItemCount > 0) return
        val at = rows.getOrNull(saved.index)?.takeIf { it.id == saved.trackId }?.let { saved.index }
            ?: rows.indexOfFirst { it.id == saved.trackId }.takeIf { it >= 0 }
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        // Tracks gone since (a rescan) shift the indices; if the current one is gone too, start at the nearest.
        player.setMediaItems(rows.map(MediaItems::of), at ?: saved.index.coerceIn(0, rows.lastIndex), if (at != null) saved.positionMs else 0)
        player.prepare()
        savedIds = rows.map { it.id }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) saveQueueSoon()
        if (events.containsAny(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
            )
        ) {
            saveState()
        }
    }

    private fun saveQueueSoon() {
        queueJob?.cancel()
        queueJob = scope.launch {
            delay(QUEUE_DEBOUNCE_MS)
            writeQueue()
        }
    }

    private suspend fun writeQueue() {
        val ids = List(player.mediaItemCount) { MediaItems.trackId(player.getMediaItemAt(it).mediaId) ?: return }
        if (ids == savedIds) return
        savedIds = ids
        // Saving is best effort: a failed write must never take the player down.
        try {
            withContext(Dispatchers.IO) { dao.replace(ids) }
        } catch (e: Exception) {
            savedIds = null
        }
    }

    private fun saveState() {
        val count = player.mediaItemCount
        settings.savedQueue = if (count == 0) {
            SavedQueueState()
        } else {
            SavedQueueState(
                size = count,
                index = player.currentMediaItemIndex,
                trackId = player.currentMediaItem?.mediaId?.let(MediaItems::trackId) ?: 0,
                positionMs = player.currentPosition.coerceAtLeast(0),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
            )
        }
    }

    /** Writes what is pending and stops; call before the player is released. */
    fun release() {
        player.removeListener(this)
        saveState()
        val pending = queueJob?.isActive == true
        queueJob?.cancel()
        scope.cancel()
        if (pending) {
            val ids = List(player.mediaItemCount) { MediaItems.trackId(player.getMediaItemAt(it).mediaId) ?: return }
            if (ids != savedIds) {
                // The process may be about to end: write now, not on a coroutine that could be cut off.
                try {
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) { dao.replace(ids) }
                } catch (e: Exception) {
                    // Best effort, as above.
                }
            }
        }
    }

    private companion object {
        const val QUEUE_DEBOUNCE_MS = 1_000L
        const val SAVE_EVERY_MS = 10_000L
    }
}
