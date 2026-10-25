// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.tracks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.KeyValueRow
import io.github.libregeek.fernix.data.db.PAGE_SIZE
import io.github.libregeek.fernix.data.db.TagCondition
import io.github.libregeek.fernix.data.db.TagDao
import io.github.libregeek.fernix.data.db.TagKeyEntity
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.TrackCursor
import io.github.libregeek.fernix.data.db.TrackSort
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

data class TracksState(
    val text: String = "",
    val chips: List<TagChip> = emptyList(),
    val person: PersonChip? = null,
    val results: List<TrackResult> = emptyList(),
    val loading: Boolean = false,
    /** Everything is loaded and nothing matches (or the library has no tracks). */
    val noResults: Boolean = false,
    /** Non-null while the tag-key picker is open. */
    val keyChoices: List<TagKeyEntity>? = null,
    val valuePicker: ValuePicker? = null,
    /** The order of the list: title unless the user has set one (kept between launches). */
    val sort: TrackSort = TrackSort.DEFAULT,
    /** The sort sheet is open. */
    val sortOpen: Boolean = false,
) {
    val hasFilter get() = text.isNotBlank() || chips.isNotEmpty() || person != null
}

/**
 * Tracks: every track by title, keyset-paged, narrowed by free text and by tag and person filters
 * (spec/architecture.md §5 "Filtering", §9.3). With nothing typed and no filter, it is the whole library.
 */
class TracksViewModel(
    private val db: FernixDatabase,
    private val search: TrackSearch,
    private val playback: PlaybackController,
    /** The search text and filters are kept here, so they survive the process being killed (spec §9). */
    private val saved: SavedStateHandle,
    /** Keeps the sort between launches. */
    private val settings: Settings,
    /**
     * A filter to start with, or 0: with [initialTagKeyId] 0 it is a person (`tag_values.id`); with a
     * tag key id it is that tag key's value (`tag_values.id`), as when a tag is tapped on Track details.
     */
    initialPersonId: Long = 0,
    initialTagKeyId: Long = 0,
) : ViewModel() {

    private val _state = MutableStateFlow(TracksState(sort = settings.trackSort))
    val state: StateFlow<TracksState> = _state

    private var searchJob: Job? = null
    private var valuesJob: Job? = null
    private var done = false

    /** A page is being loaded, or is about to be: [loadMore] must not start another. */
    private var busy = false

    /** Bumped by every [rerun], so a page that belongs to an earlier filter is dropped. */
    private var generation = 0
    private var valuesDone = false

    init {
        val restored = restore()
        if (restored) {
            rerun(debounce = false)
        } else if (initialPersonId == 0L) {
            rerun(debounce = false)
        } else {
            viewModelScope.launch {
                val value = withContext(Dispatchers.IO) { db.tagDao().valueText(initialPersonId) }
                val key = if (initialTagKeyId == 0L) null else withContext(Dispatchers.IO) { db.tagDao().keyName(initialTagKeyId) }
                when {
                    value == null -> rerun(debounce = false)
                    initialTagKeyId == 0L -> setPerson(PersonChip(initialPersonId, value))
                    key == null -> rerun(debounce = false)
                    else -> {
                        _state.update { it.copy(chips = listOf(TagChip(initialTagKeyId, key, initialPersonId, value))) }
                        rerun(debounce = false)
                    }
                }
            }
        }
    }

    /** Puts back what [save] kept; false when there was nothing (a fresh start). */
    private fun restore(): Boolean {
        if (!saved.contains(KEY_TEXT)) return false
        val chips = saved.get<ArrayList<String>>(KEY_CHIPS).orEmpty().mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size == 4) TagChip(p[0].toLong(), p[1], p[2].toLong(), p[3]) else null
        }
        val person = saved.get<String>(KEY_PERSON)?.split(SEP)?.takeIf { it.size == 2 }
            ?.let { PersonChip(it[0].toLong(), it[1]) }
        _state.update { it.copy(text = saved.get<String>(KEY_TEXT).orEmpty(), chips = chips, person = person) }
        return true
    }

    /** Keeps the text and the filters in the saved state; called whenever they may have changed. */
    private fun save() {
        val s = _state.value
        saved[KEY_TEXT] = s.text
        saved[KEY_PERSON] = s.person?.let { "${it.valueId}$SEP${it.name}" }
        saved[KEY_CHIPS] = ArrayList(s.chips.map { "${it.keyId}$SEP${it.key}$SEP${it.valueId}$SEP${it.value}" })
    }

    /** Lists again from the first page with the same text and filters, e.g. after a scan changed the library. */
    fun refresh() = rerun(debounce = false)

    fun setText(text: String) {
        _state.update { it.copy(text = text) }
        rerun(debounce = true)
    }

    fun setPerson(person: PersonChip?) {
        _state.update { it.copy(person = person) }
        rerun(debounce = false)
    }

    /** The ✕ in the search box: removes every search term, the text and all filter chips. */
    fun openSort() = _state.update { it.copy(sortOpen = true) }

    fun closeSort() = _state.update { it.copy(sortOpen = false) }

    /** Applies the order chosen in the sort sheet (and keeps it for the next launch), then lists again. */
    fun setSort(sort: TrackSort) {
        settings.trackSort = sort
        _state.update { it.copy(sort = sort, sortOpen = false) }
        rerun(debounce = false)
    }

    fun clearAll() {
        _state.update { it.copy(text = "", chips = emptyList(), person = null) }
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
        save()
        searchJob?.cancel()
        generation++
        done = false
        // The first page is this job's; busy keeps loadMore (the list is empty, so it would fire at once) out.
        busy = true
        _state.update { it.copy(results = emptyList(), loading = true, noResults = false) }
        val gen = generation
        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            dropStalePerson()
            loadPage(gen)
        }
    }

    /** A person removed by a rescan or a split no longer filters anything (spec §9). */
    private suspend fun dropStalePerson() {
        val person = _state.value.person ?: return
        val exists = withContext(Dispatchers.IO) { db.tagDao().personExists(person.valueId) }
        if (!exists) _state.update { it.copy(person = null) }
    }

    fun loadMore() {
        if (busy || done) return
        busy = true
        val gen = generation
        searchJob = viewModelScope.launch { loadPage(gen) }
    }

    private suspend fun loadPage(gen: Int) {
        val filter = filter()
        val last = _state.value.results.lastOrNull()?.track
        val cursor = last?.let { TrackCursor(it.title, it.id) } ?: TrackCursor.START
        val page = withContext(Dispatchers.IO) { search.page(filter, cursor, sort = _state.value.sort) }
        if (gen != generation) return
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

    /** Every track matching the text and filters, all pages: to play, queue or add to a playlist. */
    suspend fun allTracks(): List<TrackRow> = withContext(Dispatchers.IO) { search.all(filter(), _state.value.sort) }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val KEY_TEXT = "text"
        const val KEY_PERSON = "person"
        const val KEY_CHIPS = "chips"

        /** Unit separator: never in a tag value (spec §5), so it can join the fields of a chip. */
        const val SEP = '\u001F'
    }
}
