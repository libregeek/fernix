// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import io.github.libregeek.fernix.data.db.SplitRuleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitRulesTest {

    private fun rule(id: Long, key: String, value: String, vararg parts: String) =
        SplitRuleRow(id, key, value, TagValues.join(parts.toList()))

    private val rules = SplitRules(
        listOf(
            rule(1, "ARTIST", "Simon; Garfunkel", "Simon", "Garfunkel"),
            rule(2, "GENRE", "Rock/Pop", "Rock", "Pop"),
        ),
    )

    @Test
    fun aMatchingValueBecomesItsParts_inPlace_andTheRuleIsReported() {
        val result = rules.apply(mapOf("TITLE" to listOf("T"), "ARTIST" to listOf("Zed", "Simon; Garfunkel", "Amy")))
        assertEquals(listOf("Zed", "Simon", "Garfunkel", "Amy"), result.tags["ARTIST"])
        assertEquals(listOf("T"), result.tags["TITLE"])
        assertEquals(setOf(1L), result.appliedRuleIds)
    }

    @Test
    fun matchingIgnoresCaseOfTheValue_butRulesAreKeyedByKey() {
        val result = rules.apply(mapOf("ARTIST" to listOf("SIMON; garfunkel"), "COMPOSER" to listOf("Simon; Garfunkel")))
        assertEquals(listOf("Simon", "Garfunkel"), result.tags["ARTIST"])
        // The same text under another key has no rule.
        assertEquals(listOf("Simon; Garfunkel"), result.tags["COMPOSER"])
    }

    @Test
    fun aPartAlreadyOnTheTrackIsNotRepeated() {
        val result = rules.apply(mapOf("ARTIST" to listOf("simon", "Simon; Garfunkel")))
        assertEquals(listOf("simon", "Garfunkel"), result.tags["ARTIST"])
    }

    @Test
    fun withoutRulesOrMatches_theTagsAreUntouched() {
        val tags = mapOf("ARTIST" to listOf("Ann"))
        assertTrue(SplitRules(emptyList()).apply(tags).tags === tags)
        val result = rules.apply(tags)
        assertEquals(tags, result.tags)
        assertEquals(emptySet<Long>(), result.appliedRuleIds)
    }
}
