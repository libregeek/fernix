// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Plays a local file through the real service, with the merged manifest of the app (no network permission). */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: MediaController
    private lateinit var file: File

    @Before
    fun connect() {
        file = File(context.cacheDir, "playback-test.opus")
        instrumentation.context.assets.open("sample.opus").use { i -> file.outputStream().use { i.copyTo(it) } }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync { future = MediaController.Builder(context, token).buildAsync() }
        controller = future!!.get(15, TimeUnit.SECONDS)
    }

    @After
    fun disconnect() {
        instrumentation.runOnMainSync {
            controller.stop()
            controller.release()
        }
        file.delete()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (onMain(condition)) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out")
    }

    @Test
    fun playsALocalFile_withoutNetworkPermission() {
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission("android.permission.ACCESS_NETWORK_STATE"),
        )
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission("android.permission.INTERNET"),
        )

        val item = MediaItem.Builder().setMediaId(MediaItems.mediaId(7)).setUri(android.net.Uri.fromFile(file)).build()
        onMain {
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
        }

        waitFor { controller.currentPosition > 300 }
        assertNull(onMain { controller.playerError })
        assertEquals(7L, MediaItems.trackId(onMain { controller.currentMediaItem!!.mediaId }))
        assertTrue(onMain { controller.playbackState } != Player.STATE_IDLE)
    }
}
