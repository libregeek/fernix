// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import io.github.libregeek.fernix.data.db.FolderDao
import io.github.libregeek.fernix.data.db.FolderEntity

/**
 * Builds the `folders` tree from MediaStore `VOLUME_NAME` + `RELATIVE_PATH` (spec/architecture.md
 * §5). A volume is a root node; every path segment below it is a child. Ids are cached for the
 * duration of a scan, so the cache grows with the number of folders, not tracks. Not thread-safe:
 * use one resolver per scan, from one coroutine.
 */
class FolderResolver(private val dao: FolderDao) {

    private val ids = HashMap<String, Long>()

    /** The id of the folder for [folderPath] (see [ScanScope.folderPath]), creating it and its ancestors. */
    suspend fun idFor(folderPath: String): Long {
        ids[folderPath]?.let { return it }
        val slash = folderPath.lastIndexOf('/')
        val parentId = if (slash < 0) null else idFor(folderPath.substring(0, slash))
        val id = dao.byPath(folderPath)?.id
            ?: dao.insert(FolderEntity(parentId = parentId, name = folderPath.substring(slash + 1), path = folderPath))
        ids[folderPath] = id
        return id
    }
}
