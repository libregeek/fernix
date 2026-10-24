// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.SplitCandidate
import io.github.libregeek.fernix.data.db.TagSplitService
import io.github.libregeek.fernix.tags.PeopleTags
import io.github.libregeek.fernix.tags.TagSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A tag key the user can choose to split; [keyId] is its `tag_keys.id`. */
data class SplitKey(val keyId: Long, val key: String, val checked: Boolean)

sealed interface SplitStage {
    /** Choosing separators and tags. */
    data object Choose : SplitStage

    data object Searching : SplitStage

    /** The preview: nothing found, or rows to check. */
    data class Preview(val rows: List<PreviewRow>) : SplitStage

    data class Applying(val done: Int, val total: Int) : SplitStage

    data class Done(val rules: Int) : SplitStage
}

data class PreviewRow(val candidate: SplitCandidate, val checked: Boolean = true)

data class SplitState(
    val stage: SplitStage = SplitStage.Choose,
    /** The three usual separators, each on or off, in [STANDARD] order. */
    val standard: List<Boolean> = STANDARD.map { true },
    /** Any other separator, e.g. ` feat. `; empty for none. */
    val custom: String = "",
    val keys: List<SplitKey> = emptyList(),
) {
    val separators: List<String>
        get() = STANDARD.filterIndexed { i, _ -> standard[i] } + listOfNotNull(custom.takeIf { it.isNotEmpty() })

    val canSearch: Boolean get() = separators.isNotEmpty() && keys.any { it.checked }

    companion object {
        val STANDARD = TagSplitter.DEFAULT_SEPARATORS
    }
}

/**
 * The Split tags flow of spec/architecture.md §5 and §9: choose separators and tags (remembered),
 * preview what would be split in the tracks of the list the dialog was opened from, then apply the
 * checked rows as library-wide rules in the background with progress.
 */
class SplitTagsViewModel(
    private val db: FernixDatabase,
    private val settings: Settings,
    /** The ids of every track in the list the dialog was opened from. */
    private val trackIds: suspend () -> List<Long>,
) : ViewModel() {

    private val service = TagSplitService(db)
    private val _state = MutableStateFlow(SplitState())
    val state: StateFlow<SplitState> = _state

    init {
        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) { db.tagDao().allKeys() }
                .filter { it.key !in TagSplitter.UNSPLITTABLE_KEYS }
            val saved = settings.splitKeys
            val separators = settings.splitSeparators
            _state.update {
                it.copy(
                    standard = SplitState.STANDARD.map { s -> s in separators },
                    custom = separators.firstOrNull { s -> s !in SplitState.STANDARD }.orEmpty(),
                    // Default: the people tags and GENRE; later, whatever was chosen last time.
                    keys = keys.map { k ->
                        SplitKey(k.id, k.key, if (saved == null) isDefault(k.key) else k.key in saved)
                    },
                )
            }
        }
    }

    private fun isDefault(key: String) = PeopleTags.isPeopleKey(key) || key == "GENRE"

    fun toggleStandard(index: Int) = _state.update {
        it.copy(standard = it.standard.mapIndexed { i, on -> if (i == index) !on else on })
    }

    fun setCustom(text: String) = _state.update { it.copy(custom = text) }

    fun toggleKey(keyId: Long) = _state.update {
        it.copy(keys = it.keys.map { k -> if (k.keyId == keyId) k.copy(checked = !k.checked) else k })
    }

    /** Remembers the choice, then lists what would be split. */
    fun search() {
        val s = _state.value
        if (!s.canSearch) return
        settings.splitSeparators = s.separators
        settings.splitKeys = s.keys.filter { it.checked }.map { it.key }
        _state.update { it.copy(stage = SplitStage.Searching) }
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                service.preview(trackIds(), s.keys.filter { it.checked }.map { it.keyId }, s.separators)
            }
            _state.update { it.copy(stage = SplitStage.Preview(rows.map(::PreviewRow))) }
        }
    }

    fun toggleRow(index: Int) = updatePreview { rows ->
        rows.mapIndexed { i, row -> if (i == index) row.copy(checked = !row.checked) else row }
    }

    fun setAll(checked: Boolean) = updatePreview { rows -> rows.map { it.copy(checked = checked) } }

    private fun updatePreview(change: (List<PreviewRow>) -> List<PreviewRow>) = _state.update {
        val stage = it.stage as? SplitStage.Preview ?: return@update it
        it.copy(stage = SplitStage.Preview(change(stage.rows)))
    }

    fun back() = _state.update { it.copy(stage = SplitStage.Choose) }

    /** Applies the checked rows as rules, library-wide. */
    fun apply() {
        val preview = _state.value.stage as? SplitStage.Preview ?: return
        val chosen = preview.rows.filter { it.checked }.map { it.candidate }
        if (chosen.isEmpty()) return
        _state.update { it.copy(stage = SplitStage.Applying(0, chosen.size)) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                service.apply(chosen) { done, total ->
                    _state.update { it.copy(stage = SplitStage.Applying(done, total)) }
                }
            }
            _state.update { it.copy(stage = SplitStage.Done(chosen.size)) }
        }
    }
}
