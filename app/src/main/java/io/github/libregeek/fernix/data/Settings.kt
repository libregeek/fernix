// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data

import android.content.Context
import android.content.SharedPreferences
import io.github.libregeek.fernix.tags.TagValues

/** User settings in SharedPreferences (spec/architecture.md §2, §5, §9). */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The tag key shown in each Now Playing slot, in order; an empty string is an empty slot.
     * Never TITLE, ALBUM or DATE, which Now Playing always shows.
     */
    var nowPlayingSlots: List<String>
        get() = prefs.getString(KEY_NOW_PLAYING_SLOTS, null)
            ?.let { TagValues.split(it).padEnd(SLOT_COUNT) }
            ?: DEFAULT_NOW_PLAYING_SLOTS
        set(value) {
            require(value.size == SLOT_COUNT) { "Expected $SLOT_COUNT slots" }
            require(value.none { it in FIXED_KEYS }) { "TITLE, ALBUM and DATE can't be slots" }
            prefs.edit().putString(KEY_NOW_PLAYING_SLOTS, TagValues.join(value)).apply()
        }

    fun resetNowPlayingSlots() {
        prefs.edit().remove(KEY_NOW_PLAYING_SLOTS).apply()
    }

    /** Separators chosen last time in the Split tags dialog. */
    var splitSeparators: List<String>
        get() = prefs.getString(KEY_SPLIT_SEPARATORS, null)?.let(TagValues::split)
            ?: DEFAULT_SPLIT_SEPARATORS
        set(value) {
            prefs.edit().putString(KEY_SPLIT_SEPARATORS, TagValues.join(value)).apply()
        }

    /** Tag keys chosen last time in the Split tags dialog; null means "use the defaults". */
    var splitKeys: List<String>?
        get() = prefs.getString(KEY_SPLIT_KEYS, null)?.let(TagValues::split)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SPLIT_KEYS) else putString(KEY_SPLIT_KEYS, TagValues.join(value))
            }.apply()
        }

    private fun List<String>.padEnd(size: Int): List<String> =
        take(size) + List((size - this.size).coerceAtLeast(0)) { "" }

    companion object {
        const val SLOT_COUNT = 3
        val DEFAULT_NOW_PLAYING_SLOTS = listOf("COMPOSER", "LYRICIST", "ARTIST")
        val DEFAULT_SPLIT_SEPARATORS = listOf(";", "/", ",")

        /** Fixed fields that can't be Now Playing slots (and can't be split). */
        val FIXED_KEYS = setOf("TITLE", "ALBUM", "DATE")

        private const val FILE_NAME = "settings"
        private const val KEY_NOW_PLAYING_SLOTS = "now_playing_slots"
        private const val KEY_SPLIT_SEPARATORS = "split_separators"
        private const val KEY_SPLIT_KEYS = "split_keys"
    }
}
