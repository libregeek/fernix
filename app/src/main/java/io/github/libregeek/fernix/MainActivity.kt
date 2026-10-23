// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libregeek.fernix.ui.FernixAppUi
import io.github.libregeek.fernix.ui.scan.ScanScopeScreen
import io.github.libregeek.fernix.ui.permission.StoragePermissionGate
import io.github.libregeek.fernix.ui.theme.FernixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FernixTheme {
                val container = (application as FernixApp).container
                StoragePermissionGate(container.settings) {
                    var scope by remember { mutableStateOf(container.settings.scanScope) }
                    if (scope == null) {
                        ScanScopeScreen(onChosen = { container.settings.scanScope = it; scope = it })
                    } else {
                        // First launch after the scope is chosen, or resuming an interrupted scan.
                        LaunchedEffect(scope) { if (!container.settings.scanCompleted) container.startScan() }
                        val scanState by container.scanner.state.collectAsStateWithLifecycle()
                        FernixAppUi(scanState = scanState, onRescan = container::startScan)
                    }
                }
            }
        }
    }
}
