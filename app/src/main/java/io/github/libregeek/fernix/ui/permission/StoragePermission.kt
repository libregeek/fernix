// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.permission

import android.Manifest
import android.os.Build

/** Where the audio-library permission stands (spec/architecture.md §3). */
enum class StoragePermissionState {
    Granted,

    /** Never requested: explain, then ask. */
    NeedsRequest,

    /** Denied once; Android will still show the dialog, so explain why it is needed and ask again. */
    Rationale,

    /** Denied and Android won't show the dialog again; the user must enable it in system settings. */
    Blocked,
}

object StoragePermission {

    /** `READ_MEDIA_AUDIO` on Android 13+, `READ_EXTERNAL_STORAGE` before. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    /**
     * [askedBefore] is whether we have requested it already; [shouldShowRationale] is Android's
     * answer, which is false both before the first request and after "don't ask again".
     */
    fun resolve(granted: Boolean, askedBefore: Boolean, shouldShowRationale: Boolean): StoragePermissionState = when {
        granted -> StoragePermissionState.Granted
        !askedBefore -> StoragePermissionState.NeedsRequest
        shouldShowRationale -> StoragePermissionState.Rationale
        else -> StoragePermissionState.Blocked
    }
}
