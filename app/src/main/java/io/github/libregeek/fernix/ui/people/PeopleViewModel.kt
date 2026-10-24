// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
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
)

/** Every person once, by name, keyset-paged (spec/architecture.md §9). */
class PeopleViewModel(private val db: FernixDatabase) : ViewModel() {

    private val _state = MutableStateFlow(PeopleState())
    val state: StateFlow<PeopleState> = _state

    private var done = false
    private var busy = false

    init {
        loadMore()
    }

    fun loadMore() {
        if (busy || done) return
        busy = true
        val last = _state.value.people.lastOrNull()
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                db.tagDao().peoplePage(last?.name ?: "", last?.valueId ?: 0, PAGE_SIZE)
            }
            if (page.size < PAGE_SIZE) done = true
            busy = false
            _state.update {
                val all = it.people + page
                it.copy(loading = false, people = all, empty = done && all.isEmpty())
            }
        }
    }
}
