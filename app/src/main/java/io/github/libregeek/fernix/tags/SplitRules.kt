// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import io.github.libregeek.fernix.data.db.SplitRuleRow

/**
 * The stored split rules, to split values while a file is ingested (spec/architecture.md §5): a
 * value that matches a rule's (key, value), ignoring case, is stored as the rule's parts, so a
 * rescan keeps what the user decided. Loaded once per scan.
 */
class SplitRules(rules: List<SplitRuleRow>) {

    private class Rule(val id: Long, val parts: List<String>)

    private val byKeyAndValue: Map<Pair<String, String>, Rule> =
        rules.associate { (it.key to it.value.lowercase()) to Rule(it.id, TagValues.split(it.parts)) }

    val isEmpty: Boolean get() = byKeyAndValue.isEmpty()

    /** The tags after the rules, plus the ids of the rules that were applied (for `split_rule_tracks`). */
    class Result(val tags: Map<String, List<String>>, val appliedRuleIds: Set<Long>)

    /** [tags] must already be through [TagNormalizer]. A value repeated by a split is kept once, in its first position. */
    fun apply(tags: Map<String, List<String>>): Result {
        if (byKeyAndValue.isEmpty()) return Result(tags, emptySet())
        val applied = LinkedHashSet<Long>()
        val result = LinkedHashMap<String, List<String>>()
        for ((key, values) in tags) {
            var changed = false
            val expanded = values.flatMap { value ->
                val rule = byKeyAndValue[key to value.lowercase()]
                if (rule == null) listOf(value) else { applied += rule.id; changed = true; rule.parts }
            }
            result[key] = if (changed) TagSplitter.dedupe(expanded) else values
        }
        return Result(result, applied)
    }
}
