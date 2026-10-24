// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore

/** Closeable stream of [MediaRow]s ordered by `mediaStoreId`. */
interface MediaRowIterator : Iterator<MediaRow>, AutoCloseable

/** Where the scanner finds audio files; MediaStore in the app, a fake in tests. */
interface MediaSource {
    fun mountedVolumes(): Set<String>

    /** The distinct folders (see [ScanScope.folderPath]) holding audio on [volumes]. */
    fun folders(volumes: Set<String>): Set<String>

    fun query(volumes: Set<String>): MediaRowIterator
}

/**
 * Discovery of spec/architecture.md §6.1: reads MediaStore's index of audio files on external
 * storage. Never touches the file system. Blocking; call from `Dispatchers.IO`.
 */
class MediaStoreSource(context: Context) : MediaSource {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /** The mounted external volumes (primary storage, SD cards, USB), by MediaStore volume name. */
    override fun mountedVolumes(): Set<String> = MediaStore.getExternalVolumeNames(appContext)

    /**
     * Audio rows on [volumes], ordered by `_ID`. The caller must close the result. Rows outside
     * the scan scope are not filtered here; [ScanDiff] does that. Volume names are bound, never
     * concatenated into the selection.
     */
    override fun query(volumes: Set<String>): MediaRows {
        if (volumes.isEmpty()) return MediaRows(null)
        val names = volumes.toList()
        val selection = "${MediaStore.Audio.Media.VOLUME_NAME} IN (${names.joinToString(",") { "?" }})"
        val cursor = resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            selection,
            names.toTypedArray(),
            "${MediaStore.Audio.Media._ID} ASC",
        )
        return MediaRows(cursor)
    }

    /**
     * For the folder chooser (spec/architecture.md §6): reads only `VOLUME_NAME` and
     * `RELATIVE_PATH`. Memory grows with the number of distinct folders, not tracks.
     */
    override fun folders(volumes: Set<String>): Set<String> {
        if (volumes.isEmpty()) return emptySet()
        val names = volumes.toList()
        val cursor = resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.VOLUME_NAME, MediaStore.Audio.Media.RELATIVE_PATH),
            "${MediaStore.Audio.Media.VOLUME_NAME} IN (${names.joinToString(",") { "?" }})",
            names.toTypedArray(),
            null,
        ) ?: return emptySet()
        val result = HashSet<String>()
        cursor.use {
            val volume = it.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME)
            val relativePath = it.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (it.moveToNext()) {
                result += ScanScope.folderPath(it.getString(volume).orEmpty(), it.getString(relativePath).orEmpty())
            }
        }
        return result
    }

    /** Iterates a MediaStore cursor as [MediaRow]s. A null cursor (query failed) is empty. */
    class MediaRows(private val cursor: Cursor?) : MediaRowIterator {
        private val id = cursor?.getColumnIndexOrThrow(MediaStore.Audio.Media._ID) ?: -1
        private val size = cursor?.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE) ?: -1
        private val modified = cursor?.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED) ?: -1
        private val added = cursor?.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED) ?: -1
        private val duration = cursor?.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION) ?: -1
        private val volume = cursor?.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME) ?: -1
        private val relativePath = cursor?.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH) ?: -1
        private val displayName = cursor?.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME) ?: -1

        override fun hasNext(): Boolean {
            val c = cursor ?: return false
            return !c.isClosed && !c.isLast && !c.isAfterLast
        }

        override fun next(): MediaRow {
            val c = cursor ?: throw NoSuchElementException()
            if (!c.moveToNext()) throw NoSuchElementException()
            return MediaRow(
                mediaStoreId = c.getLong(id),
                sizeBytes = c.getLong(size),
                dateModified = c.getLong(modified),
                displayName = c.getString(displayName).orEmpty(),
                folderPath = ScanScope.folderPath(c.getString(volume).orEmpty(), c.getString(relativePath).orEmpty()),
                durationMs = c.getLong(duration),
                dateAdded = c.getLong(added),
            )
        }

        override fun close() {
            cursor?.close()
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.VOLUME_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
    }
}
