// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.libregeek.fernix.data.db.TrackSort
import io.github.libregeek.fernix.data.scan.ScanScope
import io.github.libregeek.fernix.tags.TagValues
import kotlinx.coroutines.flow.MutableStateFlow

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
            prefs.edit { putString(KEY_NOW_PLAYING_SLOTS, TagValues.join(value)) }
        }

    /** Which theme the app uses: [ThemeMode.Fernix] (the default), Dark, or System, which is stock Material following the phone. Observe it with [themeModeFlow]. */
    var themeMode: ThemeMode
        get() = themeModeFlow.value
        set(value) {
            prefs.edit { putString(KEY_THEME, value.name) }
            themeModeFlow.value = value
        }

    val themeModeFlow = MutableStateFlow(
        // "Light" is what Fernix was called before it had its own look.
        prefs.getString(KEY_THEME, null)?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.Fernix.takeIf { saved == "Light" } }
            ?: ThemeMode.Fernix,
    )

    /** The order of the Tracks list, kept between launches (spec/architecture.md §5 "Sorting"). */
    var trackSort: TrackSort
        get() = TrackSort.decode(prefs.getString(KEY_TRACK_SORT, null))
        set(value) {
            prefs.edit { putString(KEY_TRACK_SORT, value.encode()) }
        }

    fun resetNowPlayingSlots() {
        prefs.edit { remove(KEY_NOW_PLAYING_SLOTS) }
    }

    /**
     * The delimiters the user has set for splitting a tag value (spec/architecture.md §5), used as
     * typed: ` feat. ` keeps its spaces. Empty means none, so no split is offered.
     */
    var splitDelimiters: List<String>
        get() = prefs.getString(KEY_SPLIT_DELIMITERS, null)?.let(TagValues::split) ?: DEFAULT_SPLIT_DELIMITERS
        set(value) {
            prefs.edit { putString(KEY_SPLIT_DELIMITERS, TagValues.join(value)) }
        }

    /**
     * The folders the scanner indexes (spec/architecture.md §6). Null until the user has chosen them on
     * the first-launch screen, and for an install from before folders were the only choice (it had
     * `scan_scope = all`): the first scan, or the next one, waits for that choice.
     */
    var scanScope: ScanScope?
        get() = if (prefs.contains(KEY_SCAN_FOLDERS)) ScanScope(prefs.getStringSet(KEY_SCAN_FOLDERS, null).orEmpty().toSet()) else null
        set(value) {
            prefs.edit {
                remove(KEY_LEGACY_SCOPE)
                if (value == null) remove(KEY_SCAN_FOLDERS) else putStringSet(KEY_SCAN_FOLDERS, value.folders.toSet())
            }
        }

    /**
     * Whether the storage permission has been requested before. Android reports "don't ask
     * again" the same way as "never asked" (no rationale), so this tells them apart.
     */
    var storagePermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_STORAGE_ASKED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_STORAGE_ASKED, value) }
        }

    /** Whether the equalizer is switched on. */
    var equalizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQ_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_EQ_ENABLED, value) }
        }

    /** Band levels in millibels, lowest band first; null until the user changes them. */
    var equalizerLevels: List<Int>?
        get() = prefs.getString(KEY_EQ_LEVELS, null)?.let { s -> s.split(',').mapNotNull { it.toIntOrNull() } }
        set(value) {
            prefs.edit {
                if (value == null) remove(KEY_EQ_LEVELS) else putString(KEY_EQ_LEVELS, value.joinToString(","))
            }
        }

    /** Whether the notification permission (Android 13+) has been requested; asked once, not nagged. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_NOTIFICATION_ASKED, value) }
        }

    /**
     * Whether the last scan ran to the end. False from the moment a scan starts until it finishes,
     * so an interrupted scan is resumed on the next launch.
     */
    var scanCompleted: Boolean
        get() = prefs.getBoolean(KEY_SCAN_COMPLETED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_SCAN_COMPLETED, value) }
        }

    private fun List<String>.padEnd(size: Int): List<String> =
        take(size) + List((size - this.size).coerceAtLeast(0)) { "" }

    companion object {
        const val SLOT_COUNT = 3
        val DEFAULT_NOW_PLAYING_SLOTS = listOf("COMPOSER", "LYRICIST", "ARTIST")
        val DEFAULT_SPLIT_DELIMITERS = listOf(";", "/", ",")

        /** Fixed fields that can't be Now Playing slots (and can't be split). */
        val FIXED_KEYS = setOf("TITLE", "ALBUM", "DATE")

        private const val FILE_NAME = "settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_TRACK_SORT = "track_sort"
        private const val KEY_NOW_PLAYING_SLOTS = "now_playing_slots"
        private const val KEY_SPLIT_DELIMITERS = "split_delimiters"
        private const val KEY_STORAGE_ASKED = "storage_permission_asked"
        private const val KEY_EQ_ENABLED = "equalizer_enabled"
        private const val KEY_EQ_LEVELS = "equalizer_levels"
        private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
        private const val KEY_SCAN_COMPLETED = "scan_completed"
        /** Written before folders were the only choice (`all` or `selected`); dropped when a scope is saved. */
        private const val KEY_LEGACY_SCOPE = "scan_scope"
        private const val KEY_SCAN_FOLDERS = "scan_folders"
    }
}

/** The app's theme: Fernix (the light green one with a colourful gradient, the default), Dark, or System (stock Material colours, light or dark as the phone is). */
enum class ThemeMode { Fernix, Dark, System }
