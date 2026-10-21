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

    @JvmStatic
    private external fun nativeTaglibVersion(): String

    @JvmStatic
    private external fun nativeReadProperties(fd: Int): Array<String>?
}
