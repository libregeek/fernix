// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.libregeek.fernix.ui.art.AlbumArt
import io.github.libregeek.fernix.ui.art.AlbumArtFetcher
import io.github.libregeek.fernix.ui.art.AlbumArtKeyer
import io.github.libregeek.fernix.ui.art.TrackArt
import io.github.libregeek.fernix.ui.art.TrackArtFetcher
import io.github.libregeek.fernix.ui.art.TrackArtKeyer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The real Coil pipeline over the fixture files: which picture is chosen, and what happens with none. */
@RunWith(AndroidJUnit4::class)
class TrackArtFetcherTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val files = mutableListOf<File>()

    /** id → asset, so the fake "MediaStore" can open a descriptor for each. */
    private val assets = mapOf(1L to "art.mp3", 2L to "art.flac", 3L to "art.ogg", 4L to "sample.opus")

    /** album id → its tracks' ids in disc/track order, standing in for the database. */
    private val albums = mapOf(10L to listOf(4L, 3L, 1L), 11L to listOf(4L), 12L to emptyList())

    private val open: (Long) -> ParcelFileDescriptor? = { id ->
        val asset = assets[id]
        if (asset == null) {
            null
        } else {
            val file = File(context.cacheDir, "art-$id-$asset").also { files += it }
            instrumentation.context.assets.open(asset).use { i -> file.outputStream().use { i.copyTo(it) } }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private lateinit var loader: ImageLoader

    @Before
    fun setUp() {
        loader = ImageLoader.Builder(context)
            .components {
                add(TrackArtKeyer())
                add(TrackArtFetcher.Factory(open))
                add(AlbumArtKeyer())
                add(AlbumArtFetcher.Factory({ album, limit -> albums[album].orEmpty().take(limit) }, open))
            }
            .diskCache(null)
            .build()
    }

    @After
    fun tearDown() {
        files.forEach { it.delete() }
    }

    private fun load(model: Any) = runBlocking { loader.execute(ImageRequest.Builder(context).data(model).allowHardware(false).build()) }

    private fun pixel(id: Long, model: Any = TrackArt(id)): Int {
        val result = load(model)
        assertTrue("track $id: $result", result is SuccessResult)
        val bitmap = (result as SuccessResult).image.toBitmap()
        assertEquals(4, bitmap.width)
        return bitmap.getPixel(0, 0)
    }

    @Test
    fun frontCoverIsChosen_overEarlierAndRolePictures() {
        assertEquals(0xFFFF0000.toInt(), pixel(1)) // mp3: back (green), front (red), composer (blue)
        assertEquals(0xFFFF0000.toInt(), pixel(2)) // flac: front (red), artist (blue)
    }

    @Test
    fun withoutAFrontCover_theFirstPictureIsUsed() {
        assertEquals(0xFF0000FF.toInt(), pixel(3)) // ogg: lead artist (blue) only
    }

    @Test
    fun noPictureOrUnopenableFile_isAnError_soTheUiKeepsItsPlaceholder() {
        assertTrue(load(TrackArt(4)) is ErrorResult) // opus without a picture
        assertTrue(load(TrackArt(99)) is ErrorResult) // not in the "MediaStore"
    }

    @Test
    fun albumArt_isTheCoverOfTheFirstTrackThatHasOne() {
        // Album 10: the first track (opus) has no picture, the second (ogg, blue) does; the third (mp3, red) is not reached.
        assertEquals(0xFF0000FF.toInt(), pixel(10, AlbumArt(10)))
    }

    @Test
    fun albumArt_withNoPictureAnywhereOrNoTracks_isAnError() {
        assertTrue(load(AlbumArt(11)) is ErrorResult)
        assertTrue(load(AlbumArt(12)) is ErrorResult)
        assertTrue(load(AlbumArt(404)) is ErrorResult)
    }
}
