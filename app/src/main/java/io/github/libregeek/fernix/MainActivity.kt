// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.os.Bundle
import io.github.libregeek.fernix.R
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import android.graphics.Color
import io.github.libregeek.fernix.data.ThemeMode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libregeek.fernix.ui.FernixAppUi
import io.github.libregeek.fernix.ui.scan.ScanScopeScreen
import io.github.libregeek.fernix.ui.permission.NotificationPermissionEffect
import io.github.libregeek.fernix.ui.permission.StoragePermissionGate
import io.github.libregeek.fernix.ui.theme.FernixBackground
import io.github.libregeek.fernix.ui.theme.FernixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as FernixApp).container
            val mode by container.settings.themeModeFlow.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Fernix -> false
                ThemeMode.Dark -> true
            }
            // The system bars follow the app's theme, not the phone's, so their icons stay readable.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            FernixTheme(mode) {
              FernixBackground {
                StoragePermissionGate(container.settings) {
                    var scope by remember { mutableStateOf(container.settings.scanScope) }
                    if (scope == null) {
                        ScanScopeScreen(onChosen = { container.settings.scanScope = it; scope = it })
                    } else {
                        // First launch after the scope is chosen, or resuming an interrupted scan.
                        LaunchedEffect(scope) { if (!container.settings.scanCompleted) container.startScan() }
                        val scanState by container.scanner.state.collectAsStateWithLifecycle()
                        val playback by container.playback.state.collectAsStateWithLifecycle()
                        NotificationPermissionEffect(container.settings, playback.isPlaying)
                        // The player has already moved on to the next file; say why the last one stopped.
                        val context = LocalContext.current
                        LaunchedEffect(playback.errorCount) {
                            if (playback.errorCount > 0) Toast.makeText(context, R.string.playback_error, Toast.LENGTH_SHORT).show()
                        }
                        FernixAppUi(
                            container = container,
                            scanState = scanState,
                            onRescan = container::startScan,
                            playback = playback,
                            onPlayPause = container.playback::playPause,
                            onNext = container.playback::next,
                        )
                    }
                }
              }
            }
        }
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
