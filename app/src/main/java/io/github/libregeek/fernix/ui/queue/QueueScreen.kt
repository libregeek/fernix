// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.playback.PlaybackController
import kotlin.math.roundToInt

private val ROW_HEIGHT = 64.dp

@Composable
fun QueueScreen(playback: PlaybackController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: QueueViewModel = viewModel(factory = viewModelFactory { initializer { QueueViewModel(playback) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Open on the playing item.
    var scrolled by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(state.items.isNotEmpty()) {
        if (!scrolled && state.currentIndex >= 0) {
            listState.scrollToItem(state.currentIndex)
            scrolled = true
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
            }
            Text(
                stringResource(R.string.queue),
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Box(Modifier.fillMaxSize()) {
            if (state.items.isEmpty()) {
                Text(
                    stringResource(R.string.queue_empty),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.items, key = { index, _ -> index }) { index, item ->
                    QueueRow(
                        title = item.title.ifEmpty { "—" },
                        artist = item.artist,
                        current = index == state.currentIndex,
                        onClick = { vm.jumpTo(index) },
                        onRemove = { vm.remove(index) },
                        onMoved = { steps -> vm.move(index, (index + steps).coerceIn(0, state.items.lastIndex)) },
                    )
                }
            }
        }
    }
}

/**
 * One queue row. The handle drags the row with the finger; on release the row moves by the number
 * of whole rows it was dragged over (fixed row height, so no measuring of the neighbours).
 */
@Composable
private fun QueueRow(
    title: String,
    artist: String,
    current: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoved: (Int) -> Unit,
) {
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableIntStateOf(0) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .zIndex(if (dragging > 0) 1f else 0f)
            .graphicsLayer { translationY = dragged }
            .background(
                if (dragging > 0) MaterialTheme.colorScheme.surfaceContainerHighest
                else if (current) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.isNotEmpty()) {
                Text(
                    artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.queue_remove))
        }
        Box(
            Modifier
                .size(48.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = 1 },
                        onDragEnd = {
                            val steps = (dragged / rowPx).roundToInt()
                            dragged = 0f
                            dragging = 0
                            if (steps != 0) onMoved(steps)
                        },
                        onDragCancel = { dragged = 0f; dragging = 0 },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            dragged += amount
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_drag_handle), stringResource(R.string.queue_reorder))
        }
    }
}
