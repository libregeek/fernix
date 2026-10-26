// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
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
    /** The split delimiters, in the order added. */
    val delimiters: List<String> = emptyList(),
    /** Tracks in the library, for the warning before *Re-read all tags*. */
    val trackCount: Int = 0,
)

/** Settings (spec/architecture.md §9): the Now Playing tag slots here; rescan, folders and split rules have their own parts. */
class SettingsViewModel(
    private val db: FernixDatabase,
    private val settings: Settings,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState(slots = settings.nowPlayingSlots, delimiters = settings.splitDelimiters))
    val state: StateFlow<SettingsState> = _state

    init {
        viewModelScope.launch { db.trackDao().observeCount().collect { n -> _state.update { it.copy(trackCount = n) } } }
        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) { db.tagDao().allKeys() }
                .map { it.key }
                .filter { it !in Settings.FIXED_KEYS }
            _state.update { it.copy(keyChoices = keys) }
        }
    }

    /** Sets [slot] (0-based) to [key], or empties it when [key] is empty. */
    fun setSlot(slot: Int, key: String) {
        val slots = _state.value.slots.toMutableList().also { it[slot] = key }
        settings.nowPlayingSlots = slots
        _state.update { it.copy(slots = slots) }
    }

    /** Adds [text] as typed (spaces count: ` feat. `); ignores blank and repeated delimiters. */
    fun addDelimiter(text: String) {
        if (text.isBlank() || text in _state.value.delimiters) return
        setDelimiters(_state.value.delimiters + text)
    }

    fun removeDelimiter(text: String) = setDelimiters(_state.value.delimiters - text)

    fun restoreDelimiters() = setDelimiters(Settings.DEFAULT_SPLIT_DELIMITERS)

    private fun setDelimiters(delimiters: List<String>) {
        settings.splitDelimiters = delimiters
        _state.update { it.copy(delimiters = delimiters) }
    }

    fun restoreDefaults() {
        settings.resetNowPlayingSlots()
        _state.update { it.copy(slots = settings.nowPlayingSlots) }
    }
}
