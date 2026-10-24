// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TrackEntity
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The playing track's row for the mini-player's album, date and artists (spec/architecture.md §9):
 * from the database, like Now Playing, so it follows a split or a rescan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MiniPlayerViewModel(db: FernixDatabase, playback: PlaybackController) : ViewModel() {

    val track: StateFlow<TrackEntity?> = playback.state.map { it.trackId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else db.trackDao().observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
