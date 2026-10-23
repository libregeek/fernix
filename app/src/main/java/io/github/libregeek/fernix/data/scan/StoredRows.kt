// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.database.Cursor

/** Iterates the cursor of `TrackDao.storedCursor()` as [StoredRow]s, one row at a time. */
class StoredRows(private val cursor: Cursor) : Iterator<StoredRow>, AutoCloseable {
    private val id = cursor.getColumnIndexOrThrow("mediaStoreId")
    private val size = cursor.getColumnIndexOrThrow("sizeBytes")
    private val modified = cursor.getColumnIndexOrThrow("dateModified")
    private val name = cursor.getColumnIndexOrThrow("displayName")
    private val folder = cursor.getColumnIndexOrThrow("folderPath")

    override fun hasNext(): Boolean = !cursor.isClosed && !cursor.isLast && !cursor.isAfterLast

    override fun next(): StoredRow {
        if (!cursor.moveToNext()) throw NoSuchElementException()
        return StoredRow(
            mediaStoreId = cursor.getLong(id),
            sizeBytes = cursor.getLong(size),
            dateModified = cursor.getLong(modified),
            displayName = cursor.getString(name),
            folderPath = cursor.getString(folder),
        )
    }

    override fun close() = cursor.close()
}
