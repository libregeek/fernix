// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackControllerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: PlaybackController
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        controller = onMain { PlaybackController(context) }
    }

    @After
    fun tearDown() {
        onMain {
            controller.stop()
            controller.release()
        }
        files.forEach { it.delete() }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun item(id: Long, asset: String, title: String): MediaItem {
        val file = File(context.cacheDir, "ctl-$id-$asset")
        instrumentation.context.assets.open(asset).use { i -> file.outputStream().use { i.copyTo(it) } }
        files += file
        return MediaItem.Builder()
            .setMediaId(MediaItems.mediaId(id))
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist("Artist").build())
            .build()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: (PlaybackUiState) -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition(controller.state.value)) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $what; state=${controller.state.value}")
    }

    @Test
    fun commandsBeforeConnection_areQueuedAndRun() {
        assertFalse(controller.state.value.connected)

        onMain { controller.playItems(listOf(item(1, "sample.opus", "One"), item(2, "sample.wav", "Two"))) }

        waitFor("playing") { it.isPlaying }
        val s = controller.state.value
        assertEquals(1L, s.trackId)
        assertEquals("One", s.title)
        assertEquals("Artist", s.artist)
        assertEquals(2, s.queueSize)
        assertEquals(0, s.currentIndex)
        assertNull(s.error)
    }

    @Test
    fun transport_nextPausePrevious() {
        onMain { controller.playItems(listOf(item(1, "sample.opus", "One"), item(2, "sample.wav", "Two"))) }
        waitFor("playing") { it.isPlaying }

        onMain { controller.next() }
        waitFor("second track") { it.trackId == 2L }

        onMain { controller.playPause() }
        waitFor("paused") { !it.isPlaying }

        onMain { controller.previous() }
        waitFor("first track") { it.trackId == 1L }
    }

    @Test
    fun enqueue_appendsAndEmptyQueueStartsPlaying() {
        onMain { controller.enqueueItems(listOf(item(1, "sample.opus", "One"))) }
        waitFor("playing") { it.isPlaying && it.queueSize == 1 }

        onMain { controller.enqueueItems(listOf(item(2, "sample.wav", "Two"))) }
        waitFor("two queued") { it.queueSize == 2 }
        assertEquals(1L, controller.state.value.trackId)
    }

    @Test
    fun queueEditing_skipMoveRemove() {
        onMain {
            controller.playItems(listOf(item(1, "sample.opus", "One"), item(2, "sample.wav", "Two"), item(3, "sample.opus", "Three")))
        }
        waitFor("playing") { it.isPlaying }

        onMain { controller.skipTo(2) }
        waitFor("third track") { it.trackId == 3L }

        onMain { controller.removeFromQueue(0) }
        waitFor("two left") { it.queueSize == 2 && it.trackId == 3L }
        assertEquals(1, controller.state.value.currentIndex)

        onMain { controller.moveInQueue(1, 0) }
        waitFor("moved") { it.currentIndex == 0 && it.trackId == 3L }
    }

    @Test
    fun shuffleAndRepeat_areReflected() {
        onMain { controller.playItems(listOf(item(1, "sample.opus", "One"), item(2, "sample.wav", "Two")), shuffle = true) }
        waitFor("shuffle on") { it.shuffle }

        onMain { controller.setShuffle(false); controller.setRepeatMode(Player.REPEAT_MODE_ALL) }
        waitFor("repeat all") { !it.shuffle && it.repeatMode == Player.REPEAT_MODE_ALL }
    }

    @Test
    fun emptyPlay_doesNothing() {
        onMain { controller.playItems(emptyList()); controller.connect() }
        waitFor("connected") { it.connected }
        assertEquals(0, controller.state.value.queueSize)
        assertTrue(controller.state.value.trackId == null)
    }
}
