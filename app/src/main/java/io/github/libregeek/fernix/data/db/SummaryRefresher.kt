// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import io.github.libregeek.fernix.tags.PeopleTags

/**
 * Recomputes the summary tables for just the (key, value) pairs a scan batch or a split touched
 * (spec/architecture.md §5): `key_values` per pair, `people` per value. Call inside the writer's
 * transaction.
 */
class SummaryRefresher(private val db: FernixDatabase) {

    suspend fun refresh(touched: Collection<TagPair>) {
        if (touched.isEmpty()) return
        val tags = db.tagDao()
        for ((keyId, valueId) in touched) {
            val count = tags.countTracks(keyId, valueId)
            if (count == 0) tags.deleteKeyValue(keyId, valueId)
            else tags.upsertKeyValues(listOf(KeyValueEntity(keyId, valueId, count)))
        }
        val peopleKeys = tags.peopleKeyIds(PeopleTags.KEYS.toList())
        if (peopleKeys.isEmpty()) return
        for (valueId in touched.mapTo(HashSet()) { it.valueId }) {
            val count = tags.countPersonTracks(valueId, peopleKeys)
            if (count == 0) tags.deletePerson(valueId)
            else tags.upsertPeople(listOf(PersonEntity(valueId, count)))
        }
    }
}
