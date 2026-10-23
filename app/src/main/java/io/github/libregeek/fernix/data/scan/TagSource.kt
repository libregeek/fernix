// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.libregeek.fernix.tags.TagReader

/** Reads a file's tags by MediaStore id (spec/architecture.md §6.3). Blocking. */
fun interface TagSource {
    /** The raw tags, or null if the file can't be opened or parsed. */
    fun read(mediaStoreId: Long): Map<String, List<String>>?
}

/** Opens the content URI and hands the file descriptor to TagLib; never touches file paths. */
class MediaStoreTagSource(context: Context) : TagSource {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun read(mediaStoreId: Long): Map<String, List<String>>? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
        val pfd = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            // Removed mid-scan, revoked access, or not readable: treated as an untagged file.
            null
        } ?: return null
        return TagReader.readProperties(pfd)
    }
}
