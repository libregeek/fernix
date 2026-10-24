// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

object FolderPaths {

    /**
     * The half-open range `[low, high)` that holds exactly the paths below [path]: they all start
     * with `path/`, and `0` is the character after `/`.
     */
    fun subtreeRange(path: String): Pair<String, String> = "$path/" to "${path}0"
}
