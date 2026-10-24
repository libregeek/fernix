// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.problems

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.Problem
import io.github.libregeek.fernix.data.ProblemKind
import io.github.libregeek.fernix.data.ProblemLog
import io.github.libregeek.fernix.data.scan.LibraryScanner
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The files that could not be indexed or played in the last 30 days, newest first (spec/architecture.md §6 *Errors*, §9). */
@Composable
fun ProblemFilesScreen(problems: ProblemLog, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val version by problems.version.collectAsStateWithLifecycle()
    val entries by produceState<List<Problem>?>(null, version) {
        value = withContext(Dispatchers.IO) { problems.entries().asReversed().toList() }
    }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
            }
            Text(stringResource(R.string.problems_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (!entries.isNullOrEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.problems_clear)) }
            }
        }
        Text(
            pluralStringResource(R.plurals.problems_detail, ProblemLog.RETENTION_DAYS, ProblemLog.RETENTION_DAYS),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val list = entries
        Box(Modifier.fillMaxSize()) {
            if (list != null && list.isEmpty()) {
                Text(
                    stringResource(R.string.problems_none),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list.orEmpty()) { problem -> ProblemRow(problem) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.problems_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { problems.clear(); confirmClear = false }) { Text(stringResource(R.string.problems_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ProblemRow(problem: Problem) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(problem.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (problem.folder.isNotEmpty()) {
            Text(
                problem.folder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${stringResource(if (problem.kind == ProblemKind.Index) R.string.problems_kind_index else R.string.problems_kind_play)}: ${reasonText(problem)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(problem.atMs)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The log keeps a code; this words it. An unknown player code is shown as it is, lower-cased and spaced. */
@Composable
private fun reasonText(problem: Problem): String = when {
    problem.reason == LibraryScanner.REASON_TAGS_UNREADABLE -> stringResource(R.string.problems_reason_tags)
    problem.reason.contains("FILE_NOT_FOUND") -> stringResource(R.string.problems_reason_missing)
    problem.reason.contains("NO_PERMISSION") -> stringResource(R.string.problems_reason_permission)
    problem.reason.contains("DECOD") || problem.reason.contains("PARSING") || problem.reason.contains("UNSUPPORTED") ->
        stringResource(R.string.problems_reason_damaged)
    else -> problem.reason.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
}
