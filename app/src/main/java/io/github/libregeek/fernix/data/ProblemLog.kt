// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ProblemKind { Index, Play }

/**
 * A file that could not be indexed or played. [name] is the file name and [folder] its MediaStore folder
 * (`<volume>/<relative path>`), never a raw filesystem path; [reason] is a short code the screen words.
 */
data class Problem(val atMs: Long, val kind: ProblemKind, val name: String, val folder: String, val reason: String)

/**
 * The log of files that could not be indexed or played (spec/architecture.md §6 *Errors*), kept in the
 * app's private storage and viewed from Settings. An entry is kept for [RETENTION_DAYS] days and then
 * dropped; a file that fails again within a day is not logged again (a damaged file would otherwise add a
 * line at every scan). One line per entry: time, kind, name, folder, reason, tab-separated. Thread-safe.
 */
class ProblemLog(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {

    private var loaded: MutableList<Problem>? = null

    private val _version = MutableStateFlow(0)

    /** Changes whenever the log does, so a screen showing it knows to read it again. */
    val version: StateFlow<Int> = _version

    @Synchronized
    fun add(kind: ProblemKind, name: String, folder: String, reason: String) {
        val now = clock()
        val list = entries()
        val problem = Problem(now, kind, clean(name), clean(folder), clean(reason))
        if (list.any { it.kind == kind && it.name == problem.name && it.folder == problem.folder && now - it.atMs < DEDUPE_MS }) return
        list += problem
        try {
            file.appendText(line(problem))
        } catch (e: java.io.IOException) {
            // Keeping the log is best effort: a full disk must not turn a skipped file into a crash.
        }
        _version.value++
    }

    /** The entries of the last [RETENTION_DAYS] days, oldest first (the file's order). */
    @Synchronized
    fun entries(): MutableList<Problem> {
        loaded?.let { return pruned(it) }
        val read = ArrayList<Problem>()
        if (file.exists()) {
            try {
                file.forEachLine { parse(it)?.let(read::add) }
            } catch (e: java.io.IOException) {
                // An unreadable log is an empty one.
            }
        }
        loaded = read
        return pruned(read)
    }

    @Synchronized
    fun clear() {
        loaded = ArrayList()
        try {
            file.delete()
        } catch (e: SecurityException) {
            // Nothing more to do.
        }
        _version.value++
    }

    /** Drops what is older than the retention (and the oldest beyond [MAX_ENTRIES]), and rewrites the file if so. */
    private fun pruned(list: MutableList<Problem>): MutableList<Problem> {
        val limit = clock() - TimeUnit.DAYS.toMillis(RETENTION_DAYS.toLong())
        val before = list.size
        list.removeAll { it.atMs < limit }
        while (list.size > MAX_ENTRIES) list.removeAt(0)
        if (list.size != before) {
            try {
                file.writeText(list.joinToString("") { line(it) })
            } catch (e: java.io.IOException) {
                // Best effort, as above.
            }
        }
        return list
    }

    private fun line(p: Problem) = "${p.atMs}\t${p.kind.name}\t${p.name}\t${p.folder}\t${p.reason}\n"

    private fun parse(line: String): Problem? {
        val p = line.split('\t')
        if (p.size != 5) return null
        val at = p[0].toLongOrNull() ?: return null
        val kind = ProblemKind.entries.firstOrNull { it.name == p[1] } ?: return null
        return Problem(at, kind, p[2], p[3], p[4])
    }

    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    companion object {
        const val RETENTION_DAYS = 30
        private val DEDUPE_MS = TimeUnit.DAYS.toMillis(1)
        private const val MAX_ENTRIES = 5000
    }
}
