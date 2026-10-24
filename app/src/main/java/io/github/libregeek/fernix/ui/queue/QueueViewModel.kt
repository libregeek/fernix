// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.playback.QueueItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    /** Position of the playing item in [items], or -1. */
    val currentIndex: Int = -1,
)

/** The play queue from the `MediaController` (spec/architecture.md §9): jump, remove, reorder. */
class QueueViewModel(private val playback: PlaybackController) : ViewModel() {

    val state: StateFlow<QueueState> = combine(playback.queue, playback.state) { items, player ->
        QueueState(items, player.currentIndex)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueState())

    fun jumpTo(index: Int) = playback.skipTo(index)

    fun remove(index: Int) = playback.removeFromQueue(index)

    fun move(from: Int, to: Int) = playback.moveInQueue(from, to)
}
