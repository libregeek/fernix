// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

/**
 * What the scanner indexes (spec/architecture.md §6, "Scan scope"): only files inside one of [folders]
 * (`<volume>/<relativePath>` strings), sub-folders included. There is no "everything" scope; an empty
 * set indexes nothing.
 */
data class ScanScope(val folders: Set<String>) {

    /** Whether a file in [folderPath] (see [folderPath]) is inside this scope. */
    fun contains(folderPath: String) = folders.any { folderPath == it || folderPath.startsWith("$it/") }

    companion object {
        /**
         * `<volume>/<relativePath>` without the trailing slash MediaStore adds, the same format as
         * `folders.path`. A file at the root of a volume has just the volume name.
         */
        fun folderPath(volume: String, relativePath: String): String {
            val relative = relativePath.trim('/')
            return if (relative.isEmpty()) volume else "$volume/$relative"
        }

        /** The volume name of a [folderPath]. */
        fun volumeOf(folderPath: String): String = folderPath.substringBefore('/')
    }
}
