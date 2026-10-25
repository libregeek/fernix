// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.scan.MediaSource
import io.github.libregeek.fernix.data.scan.ScanScope

/**
 * First-launch screen (spec/architecture.md §6): the user adds the folders to scan, through the in-app
 * [FolderChooserScreen]; nothing is scanned before that. It is also shown once to an install from before
 * folders were the only choice, whose library stays as it is until the choice is made (and confirmed, if
 * it would remove tracks).
 */
@Composable
fun ScanScopeScreen(db: FernixDatabase, media: MediaSource, onChosen: (ScanScope) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val change = rememberScopeChange(db, media, onChosen)

    if (choosing) {
        FolderChooserScreen(
            initialSelection = emptySet(),
            onDone = { choosing = false; change(ScanScope(it)) },
            onCancel = { choosing = false },
            media = media,
        )
        return
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.scope_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.scope_intro), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scope_add)) }
        }
    }
}
