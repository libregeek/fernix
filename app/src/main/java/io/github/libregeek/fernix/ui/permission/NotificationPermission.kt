// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.permission

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.libregeek.fernix.data.Settings

/** When to ask for `POST_NOTIFICATIONS` (spec/architecture.md §3). */
object NotificationPermission {

    /**
     * Ask once, the first time playback starts, on Android 13+ if not already granted. Playback
     * works without it; only the notification and lock-screen controls are lost.
     */
    fun shouldRequest(sdkInt: Int, granted: Boolean, askedBefore: Boolean, isPlaying: Boolean): Boolean =
        sdkInt >= 33 && !granted && !askedBefore && isPlaying
}

/** Requests the notification permission when playback first starts; draws nothing. */
@Composable
fun NotificationPermissionEffect(settings: Settings, isPlaying: Boolean) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        settings.notificationPermissionAsked = true
    }
    LaunchedEffect(isPlaying) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (NotificationPermission.shouldRequest(Build.VERSION.SDK_INT, granted, settings.notificationPermissionAsked, isPlaying)) {
            // shouldRequest is false below API 33, where the permission doesn't exist.
            @SuppressLint("InlinedApi")
            val permission = Manifest.permission.POST_NOTIFICATIONS
            launcher.launch(permission)
        }
    }
}
