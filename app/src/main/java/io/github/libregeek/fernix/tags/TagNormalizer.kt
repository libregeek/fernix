// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

/** Cleans the raw tags of a file once at ingestion (spec/architecture.md §5, "Value normalisation"). */
object TagNormalizer {

    const val MAX_VALUE_LENGTH = 512

    /** Not stored in the tag tables; still readable from the file through TagLib. */
    private val EXCLUDED_KEYS = setOf("LYRICS", "ACOUSTID_FINGERPRINT")

    /**
     * Uppercases keys, trims values, and drops empty values, values longer than
     * [MAX_VALUE_LENGTH], excluded keys, and keys left without values. A value repeated under the
     * same key (ignoring case) is kept once, in its first position: `track_tags` has one row per
     * (track, key, value). Key and value order is preserved.
     */
    fun normalize(raw: Map<String, List<String>>): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for ((rawKey, rawValues) in raw) {
            val key = rawKey.trim().uppercase()
            if (key.isEmpty() || key in EXCLUDED_KEYS) continue
            val values = result.getOrPut(key) { mutableListOf() }
            for (rawValue in rawValues) {
                val value = rawValue.trim()
                if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) continue
                if (values.none { it.equals(value, ignoreCase = true) }) values += value
            }
        }
        result.values.removeAll { it.isEmpty() }
        return result
    }
}
