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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the TagLib JNI bridge against the sample files in androidTest/assets, which
 * scripts/generate-test-fixtures.py writes. Expected values below are the ones it sets.
 */
@RunWith(AndroidJUnit4::class)
class TagReaderTest {

    private fun read(asset: String): Map<String, List<String>>? {
        // An asset's descriptor points into the APK (and compressed assets have none), so copy
        // it to a real file. Like a MediaStore descriptor, this one covers exactly the whole file.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, asset)
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try {
            // readProperties consumes (closes) the descriptor it is given.
            return TagReader.readProperties(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            )
        } finally {
            file.delete()
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
        val pfd = ParcelFileDescriptor.createPipe()[0]
        val fd = pfd.detachFd()
        // A pipe is not seekable, so TagLib cannot read it as a file.
        assertNull(TagReader.readProperties(ParcelFileDescriptor.adoptFd(fd)))
    }
}
