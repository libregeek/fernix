// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.libregeek.fernix.ui.FernixAppUi
import io.github.libregeek.fernix.ui.theme.FernixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FernixTheme {
                FernixAppUi()
            }
        }
    }
}
