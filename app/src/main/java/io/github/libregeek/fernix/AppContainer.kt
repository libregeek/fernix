// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.content.Context
import io.github.libregeek.fernix.data.Settings

/**
 * Manual dependency container, owned by [FernixApp] (spec/architecture.md §2). Add the database,
 * scanner and playback controller here as they are built.
 */
class AppContainer(context: Context) {
    val settings: Settings by lazy { Settings(context) }
}
