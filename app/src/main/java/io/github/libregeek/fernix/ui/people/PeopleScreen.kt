// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase

@Composable
fun PeopleScreen(db: FernixDatabase, onOpenPerson: (Long) -> Unit, modifier: Modifier = Modifier) {
    val vm: PeopleViewModel = viewModel(factory = viewModelFactory { initializer { PeopleViewModel(db) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(listState, state.people.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.people.size - LOAD_AHEAD) vm.loadMore() }
    }

    Box(modifier.fillMaxSize()) {
        if (state.empty) {
            Text(
                stringResource(R.string.people_empty),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.people, key = { it.valueId }) { person ->
                ListItem(
                    modifier = Modifier.clickable { onOpenPerson(person.valueId) },
                    headlineContent = { Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.track_count, person.trackCount, person.trackCount))
                    },
                )
            }
        }
    }
}

private const val LOAD_AHEAD = 20
