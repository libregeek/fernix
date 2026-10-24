// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.SplitRuleRow
import io.github.libregeek.fernix.data.scan.TagSource
import io.github.libregeek.fernix.tags.TagValues
import kotlinx.coroutines.launch

@Composable
fun SplitRulesScreen(db: FernixDatabase, tags: TagSource, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SplitRulesViewModel = viewModel(factory = viewModelFactory { initializer { SplitRulesViewModel(db, tags) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<SplitRuleRow?>(null) }
    var affected by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState, state.rules.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.rules.size - LOAD_AHEAD) vm.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = state.working == null) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
            }
            Text(stringResource(R.string.settings_split_rules), style = MaterialTheme.typography.titleMedium)
        }
        state.working?.let { (done, total) ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.split_rule_working, done, total), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done / total.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (state.empty) {
                Text(
                    stringResource(R.string.split_rules_empty),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.rules, key = { it.id }) { rule ->
                    ListItem(
                        headlineContent = { Text("${rule.key}: ${rule.value}") },
                        supportingContent = {
                            Text("→ " + TagValues.split(rule.parts).joinToString(" | "), color = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            IconButton(
                                enabled = state.working == null,
                                onClick = { scope.launch { affected = vm.trackCount(rule); confirming = rule } },
                            ) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.split_rule_delete))
                            }
                        },
                    )
                }
            }
        }
    }

    confirming?.let { rule ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            text = { Text(stringResource(R.string.split_rule_delete_confirm, affected, rule.value)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(rule); confirming = null }) { Text(stringResource(R.string.split_rule_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val LOAD_AHEAD = 20
