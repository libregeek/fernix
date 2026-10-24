// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.tags.TagSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    /** The tag key in each Now Playing slot; an empty string is an empty slot. */
    val slots: List<String> = emptyList(),
    /** Every tag key in the library that a slot can show (never TITLE, ALBUM or DATE). */
    val keyChoices: List<String> = emptyList(),
)

/** Settings (spec/architecture.md §9): the Now Playing tag slots here; rescan, folders and split rules have their own parts. */
class SettingsViewModel(
    private val db: FernixDatabase,
    private val settings: Settings,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState(slots = settings.nowPlayingSlots))
    val state: StateFlow<SettingsState> = _state

    init {
        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) { db.tagDao().allKeys() }
                .map { it.key }
                .filter { it !in TagSplitter.UNSPLITTABLE_KEYS }
            _state.update { it.copy(keyChoices = keys) }
        }
    }

    /** Sets [slot] (0-based) to [key], or empties it when [key] is empty. */
    fun setSlot(slot: Int, key: String) {
        val slots = _state.value.slots.toMutableList().also { it[slot] = key }
        settings.nowPlayingSlots = slots
        _state.update { it.copy(slots = slots) }
    }

    fun restoreDefaults() {
        settings.resetNowPlayingSlots()
        _state.update { it.copy(slots = settings.nowPlayingSlots) }
    }
}
