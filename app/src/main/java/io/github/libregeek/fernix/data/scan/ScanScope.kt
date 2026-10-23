// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

/** What the scanner indexes (spec/architecture.md §6, "Scan scope"). */
sealed interface ScanScope {

    /** Whether a file in [folderPath] (see [folderPath]) is inside this scope. */
    fun contains(folderPath: String): Boolean

    /** Every audio file MediaStore reports on a mounted external volume. */
    data object All : ScanScope {
        override fun contains(folderPath: String) = true
    }

    /**
     * Only files inside one of [folders] (`<volume>/<relativePath>` strings), sub-folders included.
     * An empty set indexes nothing.
     */
    data class Selected(val folders: Set<String>) : ScanScope {
        override fun contains(folderPath: String) =
            folders.any { folderPath == it || folderPath.startsWith("$it/") }
    }

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
