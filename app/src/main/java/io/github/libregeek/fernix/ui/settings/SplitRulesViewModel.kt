// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PAGE_SIZE
import io.github.libregeek.fernix.data.db.SplitRuleRow
import io.github.libregeek.fernix.data.scan.SplitUndo
import io.github.libregeek.fernix.data.scan.TagSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SplitRulesState(
    val loading: Boolean = true,
    val rules: List<SplitRuleRow> = emptyList(),
    val empty: Boolean = false,
    /** Set while a deleted rule's tracks are being read again: (done, total). */
    val working: Pair<Int, Int>? = null,
)

/** The split rules, keyset-paged by id; deleting one reads its tracks' files again (spec/architecture.md §5). */
class SplitRulesViewModel(
    private val db: FernixDatabase,
    private val tags: TagSource,
) : ViewModel() {

    private val _state = MutableStateFlow(SplitRulesState())
    val state: StateFlow<SplitRulesState> = _state

    private var done = false
    private var busy = false

    init {
        loadMore()
    }

    fun loadMore() {
        if (busy || done) return
        busy = true
        val after = _state.value.rules.lastOrNull()?.id ?: 0
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) { db.splitRuleDao().pageWithKeys(after, PAGE_SIZE) }
            if (page.size < PAGE_SIZE) done = true
            busy = false
            _state.update {
                val all = it.rules + page
                it.copy(loading = false, rules = all, empty = done && all.isEmpty())
            }
        }
    }

    /** How many tracks deleting the rule will read again, for the confirmation. */
    suspend fun trackCount(rule: SplitRuleRow): Int = withContext(Dispatchers.IO) { db.splitRuleDao().trackCount(rule.id) }

    fun delete(rule: SplitRuleRow) {
        _state.update { it.copy(working = 0 to 0) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SplitUndo(db, tags).delete(rule.id) { done, total -> _state.update { it.copy(working = done to total) } }
            }
            _state.update {
                val rest = it.rules - rule
                it.copy(rules = rest, empty = done && rest.isEmpty(), working = null)
            }
        }
    }
}
