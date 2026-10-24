// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.KeyValueRow
import io.github.libregeek.fernix.data.db.PAGE_SIZE
import io.github.libregeek.fernix.data.db.TagCondition
import io.github.libregeek.fernix.data.db.TagDao
import io.github.libregeek.fernix.data.db.TagKeyEntity
import io.github.libregeek.fernix.data.db.TrackCursor
import io.github.libregeek.fernix.data.db.TrackFilter
import io.github.libregeek.fernix.data.db.TrackResult
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A tag filter shown as a removable chip. */
data class TagChip(val keyId: Long, val key: String, val valueId: Long, val value: String)

data class PersonChip(val valueId: Long, val name: String)

/** The value picker: the values of [key], narrowed by the typed [prefix]. */
data class ValuePicker(
    val key: TagKeyEntity,
    val prefix: String = "",
    val values: List<KeyValueRow> = emptyList(),
    val loading: Boolean = true,
)

data class SearchState(
    val text: String = "",
    val chips: List<TagChip> = emptyList(),
    val person: PersonChip? = null,
    val results: List<TrackResult> = emptyList(),
    val loading: Boolean = false,
    /** A filter is set and nothing matches. */
    val noResults: Boolean = false,
    /** Non-null while the tag-key picker is open. */
    val keyChoices: List<TagKeyEntity>? = null,
    val valuePicker: ValuePicker? = null,
) {
    val hasFilter get() = text.isNotBlank() || chips.isNotEmpty() || person != null
}

/**
 * Search: free text plus tag and person filters, all ANDed, title-sorted and keyset-paged
 * (spec/architecture.md §5 "Filtering", §9). Nothing is listed until a filter is set.
 */
class SearchViewModel(
    private val db: FernixDatabase,
    private val search: TrackSearch,
    private val playback: PlaybackController,
    /** `tag_values.id` of a person to filter by from the start, or 0. */
    initialPersonId: Long = 0,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state

    private var searchJob: Job? = null
    private var valuesJob: Job? = null
    private var done = false
    private var busy = false
    private var valuesDone = false

    init {
        if (initialPersonId != 0L) {
            viewModelScope.launch {
                val name = withContext(Dispatchers.IO) { db.tagDao().valueText(initialPersonId) }
                if (name != null) setPerson(PersonChip(initialPersonId, name))
            }
        }
    }

    fun setText(text: String) {
        _state.update { it.copy(text = text) }
        rerun(debounce = true)
    }

    fun setPerson(person: PersonChip?) {
        _state.update { it.copy(person = person) }
        rerun(debounce = false)
    }

    fun removeChip(chip: TagChip) {
        _state.update { it.copy(chips = it.chips - chip) }
        rerun(debounce = false)
    }

    private fun filter(): TrackFilter {
        val s = _state.value
        return TrackFilter(s.text, s.chips.map { TagCondition(it.keyId, it.valueId) }, s.person?.valueId)
    }

    /** Restarts the query from the first page; a page still in flight is dropped. */
    private fun rerun(debounce: Boolean) {
        searchJob?.cancel()
        done = false
        busy = false
        if (!_state.value.hasFilter) {
            _state.update { it.copy(results = emptyList(), loading = false, noResults = false) }
            return
        }
        _state.update { it.copy(results = emptyList(), loading = true, noResults = false) }
        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            dropStalePerson()
            loadPage()
        }
    }

    /** A person removed by a rescan or a split no longer filters anything (spec §9). */
    private suspend fun dropStalePerson() {
        val person = _state.value.person ?: return
        val exists = withContext(Dispatchers.IO) { db.tagDao().personExists(person.valueId) }
        if (!exists) _state.update { it.copy(person = null) }
    }

    fun loadMore() {
        if (busy || done || !_state.value.hasFilter) return
        searchJob = viewModelScope.launch { loadPage() }
    }

    private suspend fun loadPage() {
        busy = true
        val filter = filter()
        val last = _state.value.results.lastOrNull()?.track
        val cursor = last?.let { TrackCursor(it.title, it.id) } ?: TrackCursor.START
        val page = withContext(Dispatchers.IO) { search.page(filter, cursor) }
        if (page.size < PAGE_SIZE) done = true
        busy = false
        _state.update {
            val all = it.results + page
            it.copy(results = all, loading = false, noResults = done && all.isEmpty())
        }
    }

    // --- pickers -------------------------------------------------------------------------

    fun openKeyPicker() {
        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) { db.tagDao().allKeys() }
            _state.update { it.copy(keyChoices = keys) }
        }
    }

    fun pickKey(key: TagKeyEntity) {
        _state.update { it.copy(keyChoices = null, valuePicker = ValuePicker(key)) }
        loadValues(reset = true)
    }

    fun setValuePrefix(prefix: String) {
        _state.update { it.copy(valuePicker = it.valuePicker?.copy(prefix = prefix)) }
        loadValues(reset = true)
    }

    fun loadMoreValues() = loadValues(reset = false)

    private fun loadValues(reset: Boolean) {
        val picker = _state.value.valuePicker ?: return
        if (reset) {
            valuesJob?.cancel()
            _state.update { it.copy(valuePicker = picker.copy(values = emptyList(), loading = true)) }
            valuesDone = false
        } else if (valuesJob?.isActive == true || valuesDone) {
            return
        }
        val after = if (reset) null else picker.values.lastOrNull()
        valuesJob = viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                db.tagDao().valuesPage(
                    picker.key.id,
                    TagDao.likePrefix(picker.prefix),
                    after?.value ?: "",
                    after?.valueId ?: 0,
                    PAGE_SIZE,
                )
            }
            if (page.size < PAGE_SIZE) valuesDone = true
            _state.update {
                val current = it.valuePicker ?: return@update it
                it.copy(valuePicker = current.copy(values = if (reset) page else current.values + page, loading = false))
            }
        }
    }

    fun pickValue(value: KeyValueRow) {
        val key = _state.value.valuePicker?.key ?: return
        _state.update {
            val chip = TagChip(key.id, key.key, value.valueId, value.value)
            it.copy(chips = if (chip in it.chips) it.chips else it.chips + chip, valuePicker = null)
        }
        rerun(debounce = false)
    }

    fun dismissPickers() {
        valuesJob?.cancel()
        _state.update { it.copy(keyChoices = null, valuePicker = null) }
    }

    // --- playback ------------------------------------------------------------------------

    /** Plays every result of the current filter, starting at [track]. */
    fun playFrom(track: TrackRow) {
        viewModelScope.launch {
            val all = allTracks()
            playback.play(all, all.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        }
    }

    fun playAll() {
        viewModelScope.launch { allTracks().let { if (it.isNotEmpty()) playback.play(it) } }
    }

    fun enqueueAll() {
        viewModelScope.launch { playback.enqueue(allTracks()) }
    }

    fun enqueueTrack(track: TrackRow) = playback.enqueue(listOf(track))

    private suspend fun allTracks(): List<TrackRow> = withContext(Dispatchers.IO) { search.all(filter()) }

    private companion object {
        const val DEBOUNCE_MS = 250L
    }
}
