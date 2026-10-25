// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.libregeek.fernix.FernixApp
import io.github.libregeek.fernix.MainActivity
import io.github.libregeek.fernix.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a library scan runs (spec/architecture.md §6 *Scanning in the
 * background*): a foreground service of type `dataSync` with a quiet notification showing the progress.
 * It does not scan itself; the scan runs in [io.github.libregeek.fernix.AppContainer] as before, and this
 * service lasts exactly as long as `AppContainer.scanActive`. Without it Android (MIUI especially)
 * freezes or kills a backgrounded app in the middle of a long scan.
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Within seconds of startForegroundService, whatever the scan is doing.
        show(notification(null))
        val container = (application as FernixApp).container
        scope.launch {
            combine(container.scanActive, container.scanner.state) { active, state -> active to state }.collectLatest { (active, state) ->
                if (!active) {
                    ServiceCompat.stopForeground(this@ScanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    show(notification((state as? ScanState.Running)?.processed))
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 ends a `dataSync` service after about six hours; the scan goes on, unprotected. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notification(processed: Int?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.scan_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(getString(R.string.scan_notification_title))
            .setContentText(
                if (processed == null) getString(R.string.scan_notification_starting)
                else resources.getQuantityString(R.plurals.scan_running, processed, processed),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "library_scan"
        const val NOTIFICATION_ID = 2
    }
}
