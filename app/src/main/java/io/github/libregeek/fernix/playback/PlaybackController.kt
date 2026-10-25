// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import io.github.libregeek.fernix.data.db.TrackRow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** What the UI needs to know about playback; the details of a track come from the database. */
data class PlaybackUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    /** `tracks.id` of the current item (its `mediaId`), or null with an empty queue. */
    val trackId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val queueSize: Int = 0,
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    /** Counts the errors, so the UI can tell a new one from one it has shown already. */
    val errorCount: Int = 0,
    /** Counts the times a track on a volume that is not mounted was asked for, so the UI can say so. */
    val unavailableCount: Int = 0,
)

/** One entry of the play queue, as the Queue screen shows it. [index] is its position in the queue. */
data class QueueItem(val index: Int, val trackId: Long?, val title: String, val artist: String)

/**
 * UI-side handle on the playback service (spec/architecture.md §8): wraps a [MediaController] and
 * exposes playback state as a [StateFlow]. The service is started on first use, not at app start.
 * Commands given before the connection is up are queued and run once it is.
 *
 * Main thread only, like the controller it wraps.
 */
class PlaybackController(
    context: Context,
    /** False for a track on a volume that is not mounted: it is not queued, and the user is told. */
    private val isAvailable: (TrackRow) -> Boolean = { true },
) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state

    private val _started = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits when the user starts a track or list with [play]; the shell then shows Now Playing. Not
     * emitted for [enqueue], the notification, the queue, auto-advance or a play that was refused.
     */
    val started: SharedFlow<Unit> = _started

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())

    /** The play queue in play order; rebuilt only when the queue itself changes, not on every tick. */
    val queue: StateFlow<List<QueueItem>> = _queue

    private val _equalizer = MutableStateFlow<EqualizerState?>(null)

    /** The equalizer, or null until [loadEqualizer] has answered or when the device has none. */
    val equalizer: StateFlow<EqualizerState?> = _equalizer

    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) publishQueue(player)
        }
        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = error.errorCodeName, errorCount = _state.value.errorCount + 1)
        }
    }

    /** Connects to the service if not already; also lets the UI pick up a session that is already playing. */
    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                connecting = false
                val connected = try {
                    future.get()
                } catch (e: Exception) {
                    _state.value = _state.value.copy(error = e.javaClass.simpleName)
                    pending.clear()
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                publish(connected)
                publishQueue(connected)
                while (pending.isNotEmpty()) pending.removeFirst()(connected)
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun run(command: (MediaController) -> Unit) {
        val c = controller
        if (c != null) command(c) else {
            pending.addLast(command)
            connect()
        }
    }

    /** Replaces the queue with [tracks] and starts at [startIndex]; the queue is not capped. */
    fun play(tracks: List<TrackRow>, startIndex: Int = 0, shuffle: Boolean = false) {
        val start = tracks.getOrNull(startIndex)
        val playable = tracks.filter(isAvailable)
        // The track that was asked for is out of reach, or nothing is: say so and leave the queue alone.
        if (tracks.isNotEmpty() && (playable.isEmpty() || (start != null && !isAvailable(start)))) {
            reportUnavailable()
            return
        }
        val first = start?.let { s -> playable.indexOfFirst { it.id == s.id } }?.coerceAtLeast(0) ?: 0
        playItems(playable.map(MediaItems::of), first, shuffle)
        if (playable.isNotEmpty()) _started.tryEmit(Unit)
    }

    private fun reportUnavailable() {
        _state.value = _state.value.copy(unavailableCount = _state.value.unavailableCount + 1)
    }

    fun playItems(items: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean = false) = run {
        if (items.isEmpty()) return@run
        it.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0)
        it.shuffleModeEnabled = shuffle
        it.prepare()
        it.play()
    }

    /** Appends [tracks] to the end of the queue. */
    fun enqueue(tracks: List<TrackRow>) {
        val playable = tracks.filter(isAvailable)
        if (tracks.isNotEmpty() && playable.isEmpty()) reportUnavailable() else enqueueItems(playable.map(MediaItems::of))
    }

    fun enqueueItems(items: List<MediaItem>) = run {
        if (items.isEmpty()) return@run
        val wasEmpty = it.mediaItemCount == 0
        it.addMediaItems(items)
        if (wasEmpty) {
            it.prepare()
            it.play()
        }
    }

    /** Stops playback and empties the queue. */
    fun stop() = run {
        it.stop()
        it.clearMediaItems()
    }

    fun playPause() = run { if (it.isPlaying) it.pause() else it.play() }
    fun next() = run { it.seekToNextMediaItem() }
    fun previous() = run { it.seekToPreviousMediaItem() }
    fun seekTo(positionMs: Long) = run { it.seekTo(positionMs) }
    fun skipTo(queueIndex: Int) = run { if (queueIndex in 0 until it.mediaItemCount) it.seekToDefaultPosition(queueIndex) }
    fun removeFromQueue(queueIndex: Int) = run { if (queueIndex in 0 until it.mediaItemCount) it.removeMediaItem(queueIndex) }
    fun moveInQueue(from: Int, to: Int) = run { if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) }
    fun setShuffle(enabled: Boolean) = run { it.shuffleModeEnabled = enabled }
    fun setRepeatMode(@Player.RepeatMode mode: Int) = run { it.repeatMode = mode }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun loadEqualizer() = equalizerCommand(EqualizerCommands.GET)
    fun setEqualizerEnabled(enabled: Boolean) =
        equalizerCommand(EqualizerCommands.SET_ENABLED, Bundle().apply { putBoolean(EqualizerCommands.ARG_ENABLED, enabled) })
    fun setEqualizerBand(band: Int, levelMillibels: Int) = equalizerCommand(
        EqualizerCommands.SET_BAND,
        Bundle().apply { putInt(EqualizerCommands.ARG_BAND, band); putInt(EqualizerCommands.ARG_LEVEL, levelMillibels) },
    )
    fun useEqualizerPreset(index: Int) =
        equalizerCommand(EqualizerCommands.USE_PRESET, Bundle().apply { putInt(EqualizerCommands.ARG_PRESET, index) })
    fun resetEqualizer() = equalizerCommand(EqualizerCommands.RESET)

    private fun equalizerCommand(action: String, args: Bundle = Bundle.EMPTY) = run { c ->
        val future = c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        future.addListener(
            {
                val result = try { future.get() } catch (e: Exception) { return@addListener }
                _equalizer.value = if (result.resultCode == SessionResult.RESULT_SUCCESS) EqualizerState.fromBundle(result.extras) else null
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /** The playback position, for the seek bar, which polls it while visible. */
    fun currentPositionMs(): Long = controller?.currentPosition ?: 0

    /** Disconnects from the service (it keeps playing); call when the app process is going away. */
    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        pending.clear()
        _state.value = PlaybackUiState()
        _queue.value = emptyList()
        _equalizer.value = null
    }

    private fun publishQueue(player: Player) {
        _queue.value = List(player.mediaItemCount) { i ->
            val item = player.getMediaItemAt(i)
            QueueItem(
                index = i,
                trackId = MediaItems.trackId(item.mediaId),
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = MediaItems.artistOf(item.mediaMetadata),
            )
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val meta = item?.mediaMetadata
        _state.value = PlaybackUiState(
            connected = true,
            isPlaying = player.isPlaying,
            trackId = item?.mediaId?.let(MediaItems::trackId),
            title = meta?.title?.toString().orEmpty(),
            artist = meta?.let(MediaItems::artistOf).orEmpty(),
            album = meta?.albumTitle?.toString().orEmpty(),
            durationMs = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0,
            queueSize = player.mediaItemCount,
            currentIndex = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            error = _state.value.error,
            errorCount = _state.value.errorCount,
            unavailableCount = _state.value.unavailableCount,
        )
    }
}
