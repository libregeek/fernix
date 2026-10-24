// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.scan.ScanScope

/**
 * First-launch screen (spec/architecture.md §6): what to index. "All external storage" is
 * preselected; "Choose folders" continues to the in-app [FolderChooserScreen].
 */
@Composable
fun ScanScopeScreen(
    onChosen: (ScanScope) -> Unit,
    /** The scope in force, when changing it from Settings; the choice starts from it. */
    initial: ScanScope? = null,
    /** Shown as a Cancel button when changing the scope from Settings. */
    onCancel: (() -> Unit)? = null,
) {
    var chooseFolders by rememberSaveable { mutableStateOf(initial is ScanScope.Selected) }
    var choosing by rememberSaveable { mutableStateOf(false) }

    if (choosing) {
        FolderChooserScreen(
            initialSelection = (initial as? ScanScope.Selected)?.folders.orEmpty(),
            onDone = { onChosen(ScanScope.Selected(it)) },
            onCancel = { choosing = false },
        )
        return
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.scope_title), style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScopeOption(
                    selected = !chooseFolders,
                    onSelect = { chooseFolders = false },
                    title = stringResource(R.string.scope_all),
                    detail = stringResource(R.string.scope_all_detail),
                )
                ScopeOption(
                    selected = chooseFolders,
                    onSelect = { chooseFolders = true },
                    title = stringResource(R.string.scope_selected),
                    detail = stringResource(R.string.scope_selected_detail),
                )
            }
            Button(onClick = { if (chooseFolders) choosing = true else onChosen(ScanScope.All) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scope_continue))
            }
            if (onCancel != null) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun ScopeOption(selected: Boolean, onSelect: () -> Unit, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
