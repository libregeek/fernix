// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TagDao
import io.github.libregeek.fernix.data.db.PAGE_SIZE
import io.github.libregeek.fernix.data.db.PersonRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PeopleState(
    val loading: Boolean = true,
    val people: List<PersonRow> = emptyList(),
    val empty: Boolean = false,
    val query: String = "",
)

/** Every person once, by name, keyset-paged (spec/architecture.md §9). */
class PeopleViewModel(private val db: FernixDatabase, private val saved: SavedStateHandle) : ViewModel() {

    private val _state = MutableStateFlow(PeopleState(query = saved[KEY_QUERY] ?: ""))
    val state: StateFlow<PeopleState> = _state

    private var done = false
    private var busy = false
    private var generation = 0

    init {
        loadMore()
    }

    /** Starts the list again, e.g. after a scan added or removed people. */
    fun refresh() {
        generation++
        done = false
        busy = false
        _state.value = PeopleState(query = _state.value.query)
        loadMore()
    }

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        saved[KEY_QUERY] = query
        _state.update { it.copy(query = query) }
        refresh()
    }

    fun loadMore() {
        if (busy || done) return
        busy = true
        val last = _state.value.people.lastOrNull()
        val pattern = TagDao.likeContains(_state.value.query)
        val mine = generation
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                db.tagDao().peoplePage(last?.name ?: "", last?.valueId ?: 0, pattern, PAGE_SIZE)
            }
            if (mine != generation) return@launch
            if (page.size < PAGE_SIZE) done = true
            busy = false
            _state.update {
                val all = it.people + page
                it.copy(loading = false, people = all, empty = done && all.isEmpty())
            }
        }
    }

    private companion object {
        const val KEY_QUERY = "query"
    }
}
