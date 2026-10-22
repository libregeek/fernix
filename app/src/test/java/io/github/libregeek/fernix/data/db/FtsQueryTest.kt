// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {

    @Test
    fun eachWord_becomesQuotedPrefixTerm() {
        assertEquals("\"beat*\" \"les*\"", FtsQuery.build("beat les"))
    }

    @Test
    fun extraWhitespace_isIgnored() {
        assertEquals("\"a*\" \"b*\"", FtsQuery.build("  a \t b  "))
    }

    @Test
    fun blankInput_hasNoQuery() {
        assertNull(FtsQuery.build(""))
        assertNull(FtsQuery.build("   "))
    }

    @Test
    fun quotesAndStars_cannotInjectFtsSyntax() {
        assertEquals("\"abc*\"", FtsQuery.build("\"a*bc\""))
        assertNull(FtsQuery.build("\" * \"\""))
    }

    @Test
    fun ftsOperators_stayInsideQuotes() {
        assertEquals("\"a*\" \"OR*\" \"title:b*\"", FtsQuery.build("a OR title:b"))
    }

    @Test
    fun punctuationAndUnicode_arePassedThrough() {
        assertEquals("\"ac/dc*\" \"beyoncé*\"", FtsQuery.build("ac/dc beyoncé"))
    }
}
