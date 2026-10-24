// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ProblemLogTest {

    private lateinit var file: File
    private var now = 1_000_000_000_000L

    @Before
    fun setUp() {
        file = File.createTempFile("problems", ".log").also { it.delete() }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun log() = ProblemLog(file) { now }

    private fun days(n: Int) = TimeUnit.DAYS.toMillis(n.toLong())

    @Test
    fun anEntry_isKept_andSurvivesAnotherProcess() {
        log().add(ProblemKind.Index, "a.mp3", "external_primary/Music", "TAGS_UNREADABLE")
        val read = log().entries()
        assertEquals(listOf(Problem(now, ProblemKind.Index, "a.mp3", "external_primary/Music", "TAGS_UNREADABLE")), read)
    }

    @Test
    fun entriesOlderThanThirtyDays_areDropped_andTheFileIsRewritten() {
        val first = log()
        first.add(ProblemKind.Play, "old.flac", "v/Music", "ERROR_CODE_IO_FILE_NOT_FOUND")
        now += days(29)
        first.add(ProblemKind.Index, "recent.mp3", "v/Music", "TAGS_UNREADABLE")
        assertEquals(2, log().entries().size)

        now += days(2) // old.flac is now 31 days old, recent.mp3 two
        assertEquals(listOf("recent.mp3"), log().entries().map { it.name })
        assertFalse(file.readText().contains("old.flac"))
    }

    @Test
    fun theSameFileFailingAgainWithinADay_isNotLoggedAgain_butAfterADayIs() {
        val log = log()
        log.add(ProblemKind.Index, "a.mp3", "v/Music", "TAGS_UNREADABLE")
        now += TimeUnit.HOURS.toMillis(5)
        log.add(ProblemKind.Index, "a.mp3", "v/Music", "TAGS_UNREADABLE")
        assertEquals(1, log.entries().size)

        now += TimeUnit.HOURS.toMillis(20)
        log.add(ProblemKind.Index, "a.mp3", "v/Music", "TAGS_UNREADABLE")
        assertEquals(2, log.entries().size)
    }

    @Test
    fun theSameNameAsADifferentKindOrFolder_isAnotherEntry() {
        val log = log()
        log.add(ProblemKind.Index, "a.mp3", "v/Music", "X")
        log.add(ProblemKind.Play, "a.mp3", "v/Music", "X")
        log.add(ProblemKind.Index, "a.mp3", "v/Other", "X")
        assertEquals(3, log.entries().size)
    }

    @Test
    fun tabsAndNewlinesInANameCannotBreakALine() {
        log().add(ProblemKind.Index, "we\tird\nname.mp3", "v/Mu\tsic", "X")
        val read = log().entries().single()
        assertEquals("we ird name.mp3", read.name)
        assertEquals("v/Mu sic", read.folder)
    }

    @Test
    fun clear_emptiesTheLog_andTheFile() {
        val log = log()
        log.add(ProblemKind.Index, "a.mp3", "v", "X")
        log.clear()
        assertEquals(0, log.entries().size)
        assertEquals(0, log().entries().size)
        assertFalse(file.exists())
    }

    @Test
    fun aDamagedLine_isSkipped_notFatal() {
        file.writeText("garbage\n\n1\tNOPE\ta\tb\tc\n${now}\tPlay\tgood.mp3\tv\tERROR\n")
        assertEquals(listOf("good.mp3"), log().entries().map { it.name })
    }

    @Test
    fun version_changesWhenTheLogDoes() {
        val log = log()
        val before = log.version.value
        log.add(ProblemKind.Index, "a.mp3", "v", "X")
        assertEquals(before + 1, log.version.value)
        log.clear()
        assertEquals(before + 2, log.version.value)
    }
}
