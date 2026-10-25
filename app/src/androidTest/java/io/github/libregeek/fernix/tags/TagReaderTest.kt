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
    fun mp3_paddingBetweenTagAndFirstFrame_isStillRead() {
        // Tag editors can leave more than 1024 bytes of zeros between the ID3v2 tag and the first frame,
        // which TagLib's content sniffing misses (spec §7 "File type detection").
        val original = InstrumentationRegistry.getInstrumentation().context.assets.open("sample.mp3").use { it.readBytes() }
        val tagEnd = 10 + ((original[6].toInt() shl 21) or (original[7].toInt() shl 14) or (original[8].toInt() shl 7) or original[9].toInt())
        val padded = original.copyOfRange(0, tagEnd) + ByteArray(2048) + original.copyOfRange(tagEnd, original.size)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "padded.mp3")
        try {
            file.writeBytes(padded)
            fun open() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            assertEquals(listOf("Mp3 Song"), TagReader.readProperties(open())!!["TITLE"])
            assertNotNull(TagReader.readAudioProperties(open()))
            assertNotNull(TagReader.readPictures(open()))
        } finally {
            file.delete()
        }
    }

    // --- Older ID3 versions (ID3v1, v2.2, v2.3), each as written and with padding before the first frame ---

    /** The MPEG frames of sample.mp3, without its ID3v2 tag. */
    private fun mp3Audio(): ByteArray {
        val all = InstrumentationRegistry.getInstrumentation().context.assets.open("sample.mp3").use { it.readBytes() }
        val tagEnd = 10 + ((all[6].toInt() shl 21) or (all[7].toInt() shl 14) or (all[8].toInt() shl 7) or all[9].toInt())
        return all.copyOfRange(tagEnd, all.size)
    }

    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())

    private fun be(n: Int, bytes: Int) = ByteArray(bytes) { (n shr (8 * (bytes - 1 - it)) and 0xff).toByte() }

    /** An ID3v2.[version] tag (2 or 3) of ISO-8859-1 text frames, or UTF-16 with a byte order mark when [utf16]. */
    private fun id3v2(version: Int, frames: List<Pair<String, String>>, utf16: Boolean = false): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, (id, text) ->
            val data = if (utf16) byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE) else byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
            acc + id.toByteArray() + (if (version == 2) be(data.size, 3) else be(data.size, 4) + byteArrayOf(0, 0)) + data
        }
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(body.size) + body
    }

    private fun id3v1(title: String, artist: String, album: String, year: String, track: Int, genre: Int): ByteArray {
        fun field(text: String, n: Int) = text.toByteArray(Charsets.ISO_8859_1).copyOf(n)
        return "TAG".toByteArray() + field(title, 30) + field(artist, 30) + field(album, 30) + field(year, 4) +
            field("", 28) + byteArrayOf(0, track.toByte(), genre.toByte())
    }

    /** Reads [bytes] as written, then with 2048 zero bytes between [tag] and the audio. */
    private fun readBothWays(tag: ByteArray, audio: ByteArray, trailer: ByteArray = ByteArray(0)): List<Map<String, List<String>>> {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "old-id3.mp3")
        try {
            return listOf(ByteArray(0), ByteArray(2048)).map { padding ->
                file.writeBytes(tag + padding + audio + trailer)
                TagReader.readProperties(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))!!
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun mp3_id3v1_isRead() {
        // ID3v1.1 sits at the end of the file; there is no leading tag, so only the unpadded form applies.
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "v1.mp3")
        try {
            file.writeBytes(mp3Audio() + id3v1("Old Song", "Old Artist", "Old Album", "1999", 5, 17))
            val tags = TagReader.readProperties(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))!!
            assertEquals(listOf("Old Song"), tags["TITLE"])
            assertEquals(listOf("Old Artist"), tags["ARTIST"])
            assertEquals(listOf("Old Album"), tags["ALBUM"])
            assertEquals(listOf("1999"), tags["DATE"])
            assertEquals(listOf("5"), tags["TRACKNUMBER"])
            assertEquals(listOf("Rock"), tags["GENRE"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun mp3_id3v23_isRead() {
        val tag = id3v2(3, listOf("TIT2" to "Three Song", "TPE1" to "Three Artist", "TALB" to "Three Album", "TYER" to "2004", "TRCK" to "7/9"))
        for (tags in readBothWays(tag, mp3Audio())) {
            assertEquals(listOf("Three Song"), tags["TITLE"])
            assertEquals(listOf("Three Artist"), tags["ARTIST"])
            assertEquals(listOf("Three Album"), tags["ALBUM"])
            assertEquals(listOf("2004"), tags["DATE"])
            assertEquals(listOf("7/9"), tags["TRACKNUMBER"])
        }
    }

    @Test
    fun mp3_id3v23_utf16Text_isRead() {
        val tag = id3v2(3, listOf("TIT2" to "Ünïcode Sóng", "TPE1" to "മലയാളം"), utf16 = true)
        for (tags in readBothWays(tag, mp3Audio())) {
            assertEquals(listOf("Ünïcode Sóng"), tags["TITLE"])
            assertEquals(listOf("മലയാളം"), tags["ARTIST"])
        }
    }

    @Test
    fun mp3_id3v22_isRead() {
        val tag = id3v2(2, listOf("TT2" to "Two Song", "TP1" to "Two Artist", "TAL" to "Two Album", "TYE" to "1995", "TRK" to "2/10"))
        for (tags in readBothWays(tag, mp3Audio())) {
            assertEquals(listOf("Two Song"), tags["TITLE"])
            assertEquals(listOf("Two Artist"), tags["ARTIST"])
            assertEquals(listOf("Two Album"), tags["ALBUM"])
            assertEquals(listOf("1995"), tags["DATE"])
            assertEquals(listOf("2/10"), tags["TRACKNUMBER"])
        }
    }

    @Test
    fun mp3_id3v2AndId3v1Together_prefersV2() {
        val tag = id3v2(3, listOf("TIT2" to "New Title"))
        for (tags in readBothWays(tag, mp3Audio(), id3v1("Old Title", "Old Artist", "", "", 0, 255))) {
            assertEquals(listOf("New Title"), tags["TITLE"])
        }
    }

    @Test
    fun mp3_withoutAnyTag_isReadAsEmpty() {
        for (tags in readBothWays(ByteArray(0), mp3Audio())) {
            assertTrue(tags.isEmpty())
        }
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
