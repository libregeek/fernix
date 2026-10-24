// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R

/**
 * Title and settings gear of a library screen (spec §9). With [onQuery] it also has a search icon: tapping
 * it opens a search field under the title, which stays (the icon or the system Back closes it and clears
 * the search; the cross clears the text) and the list narrows as you type. [actions] sit before the
 * search and gear icons.
 */
@Composable
fun LibraryTopBar(
    title: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    onQuery: ((String) -> Unit)? = null,
    hint: String = "",
    actions: @Composable RowScope.() -> Unit = {},
) {
    // A search left in place (the tab was left and came back, or the process was killed) stays open.
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val focus = remember { FocusRequester() }
    val close: () -> Unit = {
        searching = false
        onQuery?.invoke("")
    }
    BackHandler(enabled = searching) { close() }
    LaunchedEffect(searching) { if (searching && query.isEmpty()) runCatching { focus.requestFocus() } }

    Surface(modifier, color = androidx.compose.ui.graphics.Color.Transparent) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            // The title stays; the search icon opens the field below it and closes it again.
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                actions()
                if (onQuery != null) {
                    IconButton(onClick = { if (searching) close() else searching = true }) {
                        Icon(painterResource(R.drawable.ic_search), stringResource(if (searching) R.string.search_close else R.string.search_open))
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                }
            }
            if (searching && onQuery != null) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactSearchField(query, onQuery, hint, Modifier.weight(1f), focus)
                    // Clears the text; with nothing typed it closes the search.
                    IconButton(onClick = { if (query.isEmpty()) close() else onQuery("") }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(if (query.isEmpty()) R.string.search_close else R.string.search_clear_text))
                    }
                }
            }
        }
    }
}
