// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data

import android.content.Context
import android.content.SharedPreferences
import io.github.libregeek.fernix.data.scan.ScanScope
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

    /**
     * What the scanner indexes (spec/architecture.md §6). Null until the user has chosen on the
     * first-launch screen; the first scan waits for that choice.
     */
    var scanScope: ScanScope?
        get() = when (prefs.getString(KEY_SCAN_SCOPE, null)) {
            SCOPE_ALL -> ScanScope.All
            SCOPE_SELECTED -> ScanScope.Selected(prefs.getStringSet(KEY_SCAN_FOLDERS, null).orEmpty().toSet())
            else -> null
        }
        set(value) {
            prefs.edit().apply {
                when (value) {
                    null -> remove(KEY_SCAN_SCOPE).remove(KEY_SCAN_FOLDERS)
                    ScanScope.All -> putString(KEY_SCAN_SCOPE, SCOPE_ALL).remove(KEY_SCAN_FOLDERS)
                    is ScanScope.Selected -> putString(KEY_SCAN_SCOPE, SCOPE_SELECTED)
                        .putStringSet(KEY_SCAN_FOLDERS, value.folders.toSet())
                }
            }.apply()
        }

    /**
     * Whether the storage permission has been requested before. Android reports "don't ask
     * again" the same way as "never asked" (no rationale), so this tells them apart.
     */
    var storagePermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_STORAGE_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_STORAGE_ASKED, value).apply()
        }

    /** Whether the equalizer is switched on. */
    var equalizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQ_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()
        }

    /** Band levels in millibels, lowest band first; null until the user changes them. */
    var equalizerLevels: List<Int>?
        get() = prefs.getString(KEY_EQ_LEVELS, null)?.let { s -> s.split(',').mapNotNull { it.toIntOrNull() } }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_EQ_LEVELS) else putString(KEY_EQ_LEVELS, value.joinToString(","))
            }.apply()
        }

    /** Whether the notification permission (Android 13+) has been requested; asked once, not nagged. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()
        }

    /**
     * Whether the last scan ran to the end. False from the moment a scan starts until it finishes,
     * so an interrupted scan is resumed on the next launch.
     */
    var scanCompleted: Boolean
        get() = prefs.getBoolean(KEY_SCAN_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SCAN_COMPLETED, value).apply()
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
        private const val KEY_STORAGE_ASKED = "storage_permission_asked"
        private const val KEY_EQ_ENABLED = "equalizer_enabled"
        private const val KEY_EQ_LEVELS = "equalizer_levels"
        private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
        private const val KEY_SCAN_COMPLETED = "scan_completed"
        private const val KEY_SCAN_SCOPE = "scan_scope"
        private const val KEY_SCAN_FOLDERS = "scan_folders"
        private const val SCOPE_ALL = "all"
        private const val SCOPE_SELECTED = "selected"
    }
}
