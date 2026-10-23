// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.permission

import io.github.libregeek.fernix.ui.permission.StoragePermissionState.Blocked
import io.github.libregeek.fernix.ui.permission.StoragePermissionState.Granted
import io.github.libregeek.fernix.ui.permission.StoragePermissionState.NeedsRequest
import io.github.libregeek.fernix.ui.permission.StoragePermissionState.Rationale
import org.junit.Assert.assertEquals
import org.junit.Test

class StoragePermissionTest {

    @Test
    fun granted_winsWhateverElse() {
        assertEquals(Granted, StoragePermission.resolve(granted = true, askedBefore = false, shouldShowRationale = false))
        assertEquals(Granted, StoragePermission.resolve(granted = true, askedBefore = true, shouldShowRationale = true))
    }

    @Test
    fun neverAsked_needsRequest() {
        assertEquals(NeedsRequest, StoragePermission.resolve(granted = false, askedBefore = false, shouldShowRationale = false))
    }

    @Test
    fun deniedOnce_showsRationale() {
        assertEquals(Rationale, StoragePermission.resolve(granted = false, askedBefore = true, shouldShowRationale = true))
    }

    @Test
    fun deniedWithoutRationale_isBlocked() {
        assertEquals(Blocked, StoragePermission.resolve(granted = false, askedBefore = true, shouldShowRationale = false))
    }
}
