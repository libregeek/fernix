// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class TagDaoLikeTest {

    @Test
    fun likePrefix_appendsWildcard() {
        assertEquals("abc%", TagDao.likePrefix("abc"))
        assertEquals("%", TagDao.likePrefix(""))
    }

    @Test
    fun likePrefix_escapesWildcardsAndEscapeChar() {
        assertEquals("a\\%b\\_c\\\\d%", TagDao.likePrefix("a%b_c\\d"))
    }
}
