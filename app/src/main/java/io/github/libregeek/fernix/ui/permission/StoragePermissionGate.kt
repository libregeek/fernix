// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.Settings

/**
 * Shows [content] once the audio permission is granted; until then, an explanation and a button
 * to request it (or to open system settings when Android won't ask again). Re-checks on resume,
 * so granting it in system settings takes effect when the user comes back.
 */
@Composable
fun StoragePermissionGate(settings: Settings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permission = StoragePermission.permission

    fun current(): StoragePermissionState = StoragePermission.resolve(
        granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        askedBefore = settings.storagePermissionAsked,
        shouldShowRationale = context.findActivity()?.shouldShowRequestPermissionRationale(permission) == true,
    )

    var state by remember { mutableStateOf(current()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = current() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        settings.storagePermissionAsked = true
        state = current()
    }

    if (state == StoragePermissionState.Granted) {
        content()
        return
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                stringResource(
                    when (state) {
                        StoragePermissionState.Rationale -> R.string.permission_rationale
                        StoragePermissionState.Blocked -> R.string.permission_blocked
                        else -> R.string.permission_explanation
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (state == StoragePermissionState.Blocked) {
                Button(onClick = { context.startActivity(appSettingsIntent(context)) }) {
                    Text(stringResource(R.string.permission_open_settings))
                }
            } else {
                Button(onClick = { launcher.launch(permission) }) {
                    Text(stringResource(R.string.permission_allow))
                }
            }
        }
    }
}

private fun appSettingsIntent(context: Context) =
    Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
