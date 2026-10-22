// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix

import android.app.Application

class FernixApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
