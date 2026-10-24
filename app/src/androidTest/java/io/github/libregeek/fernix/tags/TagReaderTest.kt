// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the TagLib JNI bridge against the sample files in androidTest/assets, which
 * scripts/generate-test-fixtures.py writes. Expected values below are the ones it sets.
 */
@RunWith(AndroidJUnit4::class)
class TagReaderTest {

    private fun <T> withFile(asset: String, block: (ParcelFileDescriptor) -> T): T {
        // An asset's descriptor points into the APK (and compressed assets have none), so copy
        // it to a real file. Like a MediaStore descriptor, this one covers exactly the whole file.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, asset)
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try {
            // The readers consume (close) the descriptor they are given.
            return block(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        } finally {
            file.delete()
        }
    }

    private fun read(asset: String): Map<String, List<String>>? = withFile(asset, TagReader::readProperties)

    private fun properties(asset: String): AudioProperties? = withFile(asset, TagReader::readAudioProperties)

    private fun pictures(asset: String): List<Picture>? = withFile(asset, TagReader::readPictures)

    // Colours of the 4x4 PNGs in scripts/generate-test-fixtures.py: red, green and blue.
    private fun colour(picture: Picture): String = when {
        picture.data.size < 40 -> "?"
        // The IDAT payload differs per colour; compare by the first pixel stored in the zlib stream.
        else -> android.graphics.BitmapFactory.decodeByteArray(picture.data, 0, picture.data.size)
            .getPixel(0, 0).let { px ->
                when (px) {
                    0xFFFF0000.toInt() -> "red"
                    0xFF00FF00.toInt() -> "green"
                    0xFF0000FF.toInt() -> "blue"
                    else -> "other"
                }
            }
    }

    @Test
    fun mp3_id3v24_multiValueArtistKeepsOrder() {
        val tags = read("sample.mp3")!!
        assertEquals(listOf("Shared Artist", "Second Artist"), tags["ARTIST"])
        assertEquals(listOf("Mp3 Song"), tags["TITLE"])
        assertEquals(listOf("Mp3 Album"), tags["ALBUM"])
        assertEquals(listOf("1996"), tags["DATE"])
        assertEquals(listOf("Soundtrack"), tags["GENRE"])
        assertEquals(listOf("Mp3 Composer"), tags["COMPOSER"])
        assertEquals(listOf("Mp3 Lyricist"), tags["LYRICIST"])
        assertEquals(listOf("3/12"), tags["TRACKNUMBER"])
    }

    @Test
    fun ogg_vorbis_customTagsAndFixedFields() {
        val tags = read("sample.ogg")!!
        assertEquals(listOf("Ogg Song"), tags["TITLE"])
        assertEquals(listOf("Ogg Artist"), tags["ARTIST"])
        assertEquals(listOf("Ogg Album"), tags["ALBUM"])
        assertEquals(listOf("Ogg Composer"), tags["COMPOSER"])
        assertEquals(listOf("Ogg Lyricist"), tags["LYRICIST"])
        assertEquals(listOf("Testish"), tags["LANGUAGE"])
        assertEquals(listOf("TestDiscId-0123456789"), tags["MUSICBRAINZ_DISCID"])
    }

    @Test
    fun flac_multiValueGenreAndCustomTags() {
        val tags = read("sample.flac")!!
        assertEquals(listOf("Asian Music", "Indian Music"), tags["GENRE"])
        assertEquals(listOf("Flac Song"), tags["TITLE"])
        assertEquals(listOf("Shared Artist"), tags["ARTIST"])
        assertEquals(listOf("Various Artists"), tags["ALBUMARTIST"])
        assertEquals(listOf("2011-12-01"), tags["DATE"])
        assertEquals(listOf("111"), tags["BPM"])
        assertEquals(listOf("TEST00000001"), tags["ISRC"])
    }

    @Test
    fun opus_readsVorbisCommentFields() {
        val tags = read("sample.opus")!!
        assertEquals(listOf("Opus Song"), tags["TITLE"])
        assertEquals(listOf("Opus Artist"), tags["ARTIST"])
        assertEquals(listOf("Opus Album"), tags["ALBUM"])
        assertEquals(listOf("2020"), tags["DATE"])
        assertEquals(listOf("Opus Composer"), tags["COMPOSER"])
    }

    @Test
    fun wav_readsRiffInfoFields() {
        val tags = read("sample.wav")!!
        assertEquals(listOf("Wav Song"), tags["TITLE"])
        assertEquals(listOf("Wav Artist"), tags["ARTIST"])
        assertEquals(listOf("Wav Album"), tags["ALBUM"])
        assertEquals(listOf("2019"), tags["DATE"])
    }

    @Test
    fun embeddedPictureIsNotReportedAsATag() {
        val tags = read("sample.ogg")!!
        assertFalse(tags.keys.any { it.contains("PICTURE", ignoreCase = true) })
    }

    @Test
    fun mp3_pictures_keepFileOrder_withTypeMimeDescriptionAndBytes() {
        val found = pictures("art.mp3")!!
        assertEquals(listOf(4, 3, 11), found.map { it.type })
        assertEquals(listOf("back", "front", "Johnson"), found.map { it.description })
        assertEquals(listOf("image/png", "image/png", "image/png"), found.map { it.mimeType })
        assertEquals(listOf("green", "red", "blue"), found.map(::colour))
    }

    @Test
    fun cover_isTheFrontCover_notTheFirstPicture() {
        assertEquals("red", colour(Picture.cover(pictures("art.mp3")!!)!!))
        assertEquals("red", colour(Picture.cover(pictures("art.flac")!!)!!))
    }

    @Test
    fun cover_fallsBackToTheFirstPicture_whenThereIsNoFrontCover() {
        val found = pictures("art.ogg")!!
        assertEquals(listOf(7), found.map { it.type })
        assertEquals("Ann", found.single().description)
        assertEquals("blue", colour(Picture.cover(found)!!))
    }

    @Test
    fun flac_pictures_includeRoleTypedOnes() {
        val found = pictures("art.flac")!!
        assertEquals(listOf(3, 8), found.map { it.type })
        assertEquals(listOf("front", "Ann"), found.map { it.description })
    }

    @Test
    fun fileWithoutPictures_givesAnEmptyList_andNonAudioGivesNull() {
        assertEquals(emptyList<Picture>(), pictures("sample.opus"))
        assertNull(pictures("notaudio.txt"))
    }

    // The fixtures are 2 s mono sine waves (the WAV is 1 s at 8 kHz); see scripts/generate-test-fixtures.py.
    @Test
    fun audioProperties_wav_reportsBitsPerSample() {
        val p = properties("sample.wav")!!
        assertEquals(8000, p.sampleRateHz)
        assertEquals(1, p.channels)
        assertEquals(16, p.bitsPerSample)
        assertTrue("length ${p.lengthMs}", p.lengthMs in 950..1050)
    }

    @Test
    fun audioProperties_flac_reportsBitsPerSample() {
        val p = properties("sample.flac")!!
        assertEquals(22050, p.sampleRateHz)
        assertEquals(1, p.channels)
        assertEquals(16, p.bitsPerSample)
        assertTrue("length ${p.lengthMs}", p.lengthMs in 1900..2100)
    }

    @Test
    fun audioProperties_lossyFormats_haveNoBitsPerSample() {
        for (asset in listOf("sample.mp3", "sample.ogg", "sample.opus")) {
            val p = properties(asset)!!
            assertEquals(asset, 0, p.bitsPerSample)
            assertEquals(asset, 1, p.channels)
            assertTrue("$asset length ${p.lengthMs}", p.lengthMs in 1500..2500)
            assertTrue("$asset bitrate ${p.bitrateKbps}", p.bitrateKbps > 0)
        }
        assertEquals(22050, properties("sample.mp3")!!.sampleRateHz)
        assertEquals(32, properties("sample.mp3")!!.bitrateKbps)
    }

    @Test
    fun audioProperties_ofNonAudio_isNull() {
        assertNull(properties("notaudio.txt"))
    }

    @Test
    fun m4a_readsItunesAtomsAsPropertyKeys_withMultipleArtists() {
        val tags = read("sample.m4a")!!
        assertEquals(listOf("M4a Song"), tags["TITLE"])
        assertEquals(listOf("Shared Artist", "Second Artist"), tags["ARTIST"])
        assertEquals(listOf("M4a Album"), tags["ALBUM"])
        assertEquals(listOf("2022"), tags["DATE"])
        assertEquals(listOf("Soundtrack"), tags["GENRE"])
        assertEquals(listOf("M4a Composer"), tags["COMPOSER"])
        assertEquals(listOf("3/12"), tags["TRACKNUMBER"])
        assertFalse("the cover is not a tag", tags.keys.any { it.contains("COVR") || it.contains("PICTURE") })
    }

    @Test
    fun m4a_cover_isReadAsAPicture() {
        val found = pictures("sample.m4a")!!
        assertEquals(1, found.size)
        assertEquals("image/png", found.single().mimeType)
        assertTrue(found.single().data.isNotEmpty())
        assertNotNull(Picture.cover(found))
    }

    @Test
    fun audioProperties_m4aAac_hasNoBitDepth_becauseAacIsLossy() {
        val p = properties("sample.m4a")!!
        assertEquals(22050, p.sampleRateHz)
        assertEquals(1, p.channels)
        assertEquals(0, p.bitsPerSample)
        assertTrue("length ${p.lengthMs}", p.lengthMs in 1500..2500)
        assertTrue("bitrate ${p.bitrateKbps}", p.bitrateKbps > 0)
    }

    @Test
    fun allKeysAreUppercase() {
        for (asset in listOf("sample.mp3", "sample.ogg", "sample.flac")) {
            val tags = read(asset)!!
            assertNotNull(asset, tags)
            assertEquals(asset, tags.keys.map { it.uppercase() }, tags.keys.toList())
        }
    }

    @Test
    fun nonAudioFile_returnsNull() {
        assertNull(read("notaudio.txt"))
    }

    @Test
    fun invalidDescriptor_returnsNull() {
        val (read, write) = ParcelFileDescriptor.createPipe()
        // With the write end closed the pipe is at end-of-file; left open, TagLib would block on it.
        write.close()
        val fd = read.detachFd()
        // A pipe is not seekable, so TagLib cannot read it as a file.
        assertNull(TagReader.readProperties(ParcelFileDescriptor.adoptFd(fd)))
        val (read2, write2) = ParcelFileDescriptor.createPipe()
        write2.close()
        assertNull(TagReader.readPictures(read2))
    }
}
