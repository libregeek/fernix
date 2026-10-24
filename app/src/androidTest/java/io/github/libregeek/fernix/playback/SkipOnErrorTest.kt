// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A file that cannot be played is skipped, not a dead end (spec/architecture.md §8 *Errors*). */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SkipOnErrorTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val files = mutableListOf<File>()
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            player = PlayerFactory.create(context)
            SkipOnError(player).attach()
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player.release() }
        files.forEach { it.delete() }
    }

    private fun good(): MediaItem {
        val file = File(context.cacheDir, "skip-good.wav").also { files += it }
        instrumentation.context.assets.open("sample.wav").use { i -> file.outputStream().use { i.copyTo(it) } }
        return MediaItem.fromUri(Uri.fromFile(file))
    }

    private fun bad(name: String): MediaItem {
        val file = File(context.cacheDir, "skip-$name.mp3").also { files += it }
        file.writeBytes(ByteArray(2000) { 0x55 })
        return MediaItem.fromUri(Uri.fromFile(file))
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            if (onMain(condition)) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @Test
    fun anUnplayableFile_isSkipped_andTheNextOnePlays() {
        instrumentation.runOnMainSync {
            player.setMediaItems(listOf(bad("a"), good()))
            player.prepare()
            player.play()
        }
        waitFor("the second item to play") { player.currentMediaItemIndex == 1 && player.isPlaying }
    }

    @Test
    fun severalUnplayableFilesInARow_areAllSkipped() {
        instrumentation.runOnMainSync {
            player.setMediaItems(listOf(bad("a"), bad("b"), bad("c"), good()))
            player.prepare()
            player.play()
        }
        waitFor("the last item to play") { player.currentMediaItemIndex == 3 && player.isPlaying }
    }

    @Test
    fun aQueueOfOnlyBadFiles_stops_insteadOfLoopingForever_withRepeatAll() {
        instrumentation.runOnMainSync {
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.setMediaItems(listOf(bad("a"), bad("b")))
            player.prepare()
            player.play()
        }
        waitFor("the player to give up") { player.playbackState == Player.STATE_IDLE && player.playerError != null }
        Thread.sleep(1000)
        assertFalse(onMain { player.isPlaying })
        assertTrue(onMain { player.playerError != null })
        assertEquals(Player.STATE_IDLE, onMain { player.playbackState })
    }
}
