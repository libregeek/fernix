// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TrackRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Which tracks are out of reach because their volume is not mounted, e.g. a removed SD card (spec/architecture.md
 * §6 *Unmounted volumes*). Their rows stay in the library; the lists mark them and they cannot be played until
 * the volume is back. It keeps the ids of the folders on unmounted volumes ([unavailableFolders]), looked up again when a volume
 * is mounted or removed (a system broadcast, which needs no permission), when a scan has finished and when the app comes back to the front.
 */
class AvailableVolumes(context: Context, private val db: FernixDatabase) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _unavailableFolders = MutableStateFlow<Set<Long>>(emptySet())
    val unavailableFolders: StateFlow<Set<Long>> = _unavailableFolders

    fun isAvailable(track: TrackRow): Boolean = track.folderId !in _unavailableFolders.value

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    /** Starts listening for volumes coming and going; call once. */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun refresh() {
        scope.launch {
            val mounted = try {
                MediaStore.getExternalVolumeNames(appContext)
            } catch (e: Exception) {
                return@launch // can't tell: keep what is known rather than marking everything
            }
            val gone = db.folderDao().idsAndPaths()
                .filter { ScanScope.volumeOf(it.path) !in mounted }
                .mapTo(HashSet()) { it.id }
            _unavailableFolders.value = gone
        }
    }
}
