// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.content.Context
import androidx.room.Room
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TrackSearch

/**
 * Manual dependency container, owned by [FernixApp] (spec/architecture.md §2). Add the database,
 * scanner and playback controller here as they are built.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings: Settings by lazy { Settings(appContext) }

    val database: FernixDatabase by lazy {
        Room.databaseBuilder(appContext, FernixDatabase::class.java, FernixDatabase.FILE_NAME).build()
    }

    val trackSearch: TrackSearch by lazy { TrackSearch(database) }
}
