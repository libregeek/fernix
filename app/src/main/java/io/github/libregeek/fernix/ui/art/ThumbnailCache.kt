// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.art

import java.io.File

/**
 * The small on-disk cache of list thumbnails (spec/architecture.md §7 *Thumbnails*), in the app's cache
 * directory: one JPEG per album (or per track without an album), plus an empty `.none` marker for
 * art that doesn't exist, so a missing cover isn't searched for again on every scroll. It is
 * bounded to [maxBytes] (the oldest files are deleted first) and disposable: Android may clear it,
 * Fernix clears it after a scan that changed files, and a missing file is simply made again.
 */
class ThumbnailCache(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    private val lock = Any()

    /** The cached thumbnail for [key], or null. Reading it counts as use, so it is the last to be evicted. */
    fun get(key: String): File? {
        val file = File(dir, "$key.jpg")
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /** Whether it is known that [key] has no art. */
    fun hasNone(key: String): Boolean = File(dir, "$key.none").isFile

    fun putNone(key: String) {
        synchronized(lock) {
            dir.mkdirs()
            File(dir, "$key.none").createNewFile()
            trim()
        }
    }

    /** Stores [jpeg] under [key] (written to a temporary file first, so a reader never sees half of it). */
    fun put(key: String, jpeg: ByteArray): File {
        synchronized(lock) {
            dir.mkdirs()
            val target = File(dir, "$key.jpg")
            val temp = File(dir, "$key.tmp")
            temp.writeBytes(jpeg)
            if (!temp.renameTo(target)) {
                temp.delete()
                target.writeBytes(jpeg)
            }
            trim()
            return target
        }
    }

    /** Deletes every thumbnail and marker. */
    fun clear() {
        synchronized(lock) { dir.listFiles()?.forEach { it.delete() } }
    }

    /** Total size of the cached files in bytes. */
    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
    }
}
