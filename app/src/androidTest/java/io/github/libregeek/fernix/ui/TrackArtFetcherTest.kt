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
import io.github.libregeek.fernix.ui.art.AlbumThumbnail
import io.github.libregeek.fernix.ui.art.AlbumThumbnailFetcher
import io.github.libregeek.fernix.ui.art.AlbumThumbnailKeyer
import io.github.libregeek.fernix.ui.art.ThumbnailCache
import io.github.libregeek.fernix.ui.art.TrackThumbnail
import io.github.libregeek.fernix.ui.art.TrackThumbnailFetcher
import io.github.libregeek.fernix.ui.art.TrackThumbnailKeyer
import io.github.libregeek.fernix.ui.art.AlbumArt
import io.github.libregeek.fernix.ui.art.AlbumArtFetcher
import io.github.libregeek.fernix.ui.art.AlbumArtKeyer
import io.github.libregeek.fernix.ui.art.PersonArt
import io.github.libregeek.fernix.ui.art.PersonArtFetcher
import io.github.libregeek.fernix.ui.art.PersonArtKeyer
import io.github.libregeek.fernix.ui.art.PersonArtSource
import io.github.libregeek.fernix.ui.art.PersonArtTrack
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
        opened++
        val asset = assets[id]
        if (asset == null) {
            null
        } else {
            val file = File(context.cacheDir, "art-$id-$asset").also { files += it }
            instrumentation.context.assets.open(asset).use { i -> file.outputStream().use { i.copyTo(it) } }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    /** person id → what the database would give the fetcher: tracks with their role values. */
    private class FakePeople(val tracks: Map<Long, List<PersonArtTrack>>, val top: String?) : PersonArtSource {
        override suspend fun tracks(personId: Long, limit: Int) = tracks[personId].orEmpty().take(limit)
        override suspend fun topRoleKey(personId: Long) = top
    }

    private var people = FakePeople(emptyMap(), null)

    private val cacheDir = File(context.cacheDir, "thumbnails-test")
    private val thumbnails = ThumbnailCache(cacheDir, maxBytes = 1_000_000)
    private var opened = 0

    private lateinit var loader: ImageLoader

    @Before
    fun setUp() {
        loader = ImageLoader.Builder(context)
            .components {
                add(TrackArtKeyer())
                add(TrackArtFetcher.Factory(open))
                add(PersonArtKeyer())
                add(PersonArtFetcher.Factory(object : PersonArtSource {
                    override suspend fun tracks(personId: Long, limit: Int) = people.tracks(personId, limit)
                    override suspend fun topRoleKey(personId: Long) = people.topRoleKey(personId)
                }, open))
                add(AlbumThumbnailKeyer())
                add(AlbumThumbnailFetcher.Factory(thumbnails, { album, limit -> albums[album].orEmpty().take(limit) }, open))
                add(TrackThumbnailKeyer())
                add(TrackThumbnailFetcher.Factory(thumbnails, open))
                add(AlbumArtKeyer())
                add(AlbumArtFetcher.Factory({ album, limit -> albums[album].orEmpty().take(limit) }, open))
            }
            .diskCache(null)
            .build()
    }

    @After
    fun tearDown() {
        thumbnails.clear()
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

    // Fixtures: art.mp3 has back (green), front (red) and a COMPOSER picture "Johnson" (blue);
    // art.flac has front (red) and an ARTIST picture "Ann" (blue); art.ogg a lead-artist picture "Ann" (blue).

    @Test
    fun personImage_isTheRoleTypedPictureOfThatPerson_notTheCover() {
        people = FakePeople(mapOf(1L to listOf(PersonArtTrack(1, mapOf("COMPOSER" to listOf("Mp3 Composer"))))), "COMPOSER")
        assertEquals(0xFF0000FF.toInt(), pixel(1, PersonArt(1, "Johnson"))) // matched by description, blue
    }

    @Test
    fun personImage_searchesTheirTracksInOrder_andSkipsFilesWithoutAMatch() {
        people = FakePeople(
            mapOf(2L to listOf(PersonArtTrack(4, emptyMap()), PersonArtTrack(2, emptyMap()))), // opus: none; flac: "Ann"
            "ARTIST",
        )
        assertEquals(0xFF0000FF.toInt(), pixel(2, PersonArt(2, "Ann")))
    }

    @Test
    fun personImage_withNoMatchingPicture_isAnError_soInitialsStay() {
        people = FakePeople(mapOf(3L to listOf(PersonArtTrack(1, emptyMap()), PersonArtTrack(2, emptyMap()))), "ARTIST")
        assertTrue(load(PersonArt(3, "Nobody")) is ErrorResult) // pictures exist, none is Nobody's
        assertTrue(load(PersonArt(404, "Ann")) is ErrorResult) // no tracks
    }

    // --- thumbnails (JPEG is lossy, so colours are compared by their dominant channel) ---

    private fun isBlue(px: Int) = android.graphics.Color.blue(px) > 200 && android.graphics.Color.red(px) < 60 && android.graphics.Color.green(px) < 60

    private fun isRed(px: Int) = android.graphics.Color.red(px) > 200 && android.graphics.Color.green(px) < 60 && android.graphics.Color.blue(px) < 60

    @Test
    fun albumThumbnail_isMadeOnce_scaledDown_andThenServedFromTheCacheWithoutOpeningAnyFile() {
        // Album 10: the first track (opus) has no picture, the second (ogg) is blue.
        assertTrue("blue expected", isBlue(pixel(10, AlbumThumbnail(10))))
        val made = thumbnails.get("album-10")!!
        assertTrue(made.length() in 1..20_000)
        assertTrue(opened >= 1)

        opened = 0
        loader.memoryCache?.clear()
        val again = load(AlbumThumbnail(10))
        assertTrue(again is SuccessResult)
        assertEquals(0, opened) // read from the cache file, not from any audio file
    }

    @Test
    fun albumThumbnail_forAnAlbumWithoutArt_leavesAMarker_andIsNotLookedForAgain() {
        assertTrue(load(AlbumThumbnail(11)) is ErrorResult)
        assertTrue(thumbnails.hasNone("album-11"))
        opened = 0
        assertTrue(load(AlbumThumbnail(11)) is ErrorResult)
        assertEquals(0, opened)
    }

    @Test
    fun trackThumbnail_usesTheTracksOwnCover_andAClearedCacheIsMadeAgain() {
        assertTrue("red expected", isRed(pixel(1, TrackThumbnail(1))))
        assertTrue(thumbnails.get("track-1") != null)
        thumbnails.clear()
        loader.memoryCache?.clear()
        assertTrue(load(TrackThumbnail(1)) is SuccessResult)
        assertTrue(thumbnails.get("track-1") != null)
    }
}
