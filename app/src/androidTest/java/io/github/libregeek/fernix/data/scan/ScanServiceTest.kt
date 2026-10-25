// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.libregeek.fernix.FernixApp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The foreground service that protects a long scan (spec/architecture.md §6) lasts exactly as long as the scan. */
@RunWith(AndroidJUnit4::class)
class ScanServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Suppress("DEPRECATION") // still lists the app's own services, which is all this needs
    private fun running() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == ScanService::class.java.name }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(100) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun runsWhileAScanIsActive_andStopsWhenItEnds() {
        val container = (context as FernixApp).container
        container.scanActive.value = true
        ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
        assertTrue("the service should start", waitFor { running() })

        container.scanActive.value = false
        assertTrue("the service should stop with the scan", waitFor { !running() })
    }
}
