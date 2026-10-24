// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import android.os.ParcelFileDescriptor

/** Reads tags through TagLib (native). See spec/architecture.md §7. */
object TagReader {

    init {
        System.loadLibrary("fernix")
    }

    val taglibVersion: String by lazy { nativeTaglibVersion() }

    /**
     * Reads all tags of the file behind [pfd] as key → values (keys as TagLib's
     * PropertyMap reports them, e.g. "ARTIST"). Consumes [pfd]: its descriptor is
     * handed to native code, which closes it. Returns null if the file can't be read.
     */
    fun readProperties(pfd: ParcelFileDescriptor): Map<String, List<String>>? {
        val flat = nativeReadProperties(pfd.detachFd()) ?: return null
        val result = LinkedHashMap<String, MutableList<String>>()
        for (i in flat.indices step 2) {
            result.getOrPut(flat[i]) { mutableListOf() }.add(flat[i + 1])
        }
        return result
    }

    /**
     * Reads the embedded pictures of the file behind [pfd], in file order. Consumes [pfd] like
     * [readProperties]. An empty list means the file has none; null means it can't be read. The
     * bytes are the whole picture, so call it only for a track you are about to show.
     */
    fun readPictures(pfd: ParcelFileDescriptor): List<Picture>? {
        val flat = nativeReadPictures(pfd.detachFd()) ?: return null
        return List(flat.size / 4) { i ->
            Picture(
                type = Picture.typeOf(flat[i * 4] as String),
                mimeType = flat[i * 4 + 1] as String,
                description = flat[i * 4 + 2] as String,
                data = flat[i * 4 + 3] as ByteArray,
            )
        }
    }

    /**
     * Reads the technical details of the file behind [pfd] (consumes it, like [readProperties]).
     * Null if the file can't be read.
     */
    fun readAudioProperties(pfd: ParcelFileDescriptor): AudioProperties? {
        val v = nativeReadAudioProperties(pfd.detachFd()) ?: return null
        return AudioProperties(v[0], v[1], v[2], v[3], v[4])
    }

    @JvmStatic
    private external fun nativeTaglibVersion(): String

    @JvmStatic
    private external fun nativeReadAudioProperties(fd: Int): IntArray?

    @JvmStatic
    private external fun nativeReadPictures(fd: Int): Array<Any>?

    @JvmStatic
    private external fun nativeReadProperties(fd: Int): Array<String>?
}

/** An embedded picture; [type] is the ID3v2 `APIC` picture type (3 = front cover, 8 = artist, ...). */
class Picture(val type: Int, val mimeType: String, val description: String, val data: ByteArray) {

    companion object {
        const val FRONT_COVER = 3

        /** TagLib's names of the picture types, indexed by the type number. */
        private val TYPE_NAMES = listOf(
            "Other", "File Icon", "Other File Icon", "Front Cover", "Back Cover", "Leaflet Page", "Media",
            "Lead Artist", "Artist", "Conductor", "Band", "Composer", "Lyricist", "Recording Location",
            "During Recording", "During Performance", "Movie Screen Capture", "Coloured Fish",
            "Illustration", "Band Logo", "Publisher Logo",
        )

        fun typeOf(name: String): Int = TYPE_NAMES.indexOf(name).coerceAtLeast(0)

        /** The album art of a file: its front cover, else its first picture, else null (spec §9). */
        fun cover(pictures: List<Picture>): Picture? = pictures.firstOrNull { it.type == FRONT_COVER } ?: pictures.firstOrNull()
    }
}

/** Technical details from TagLib; a field the format doesn't report is 0 and is not shown (spec §7, §9). */
data class AudioProperties(
    val lengthMs: Int,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val channels: Int,
    /** Only FLAC, WAV and ALAC report it. */
    val bitsPerSample: Int,
)
