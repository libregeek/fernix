// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.art

import io.github.libregeek.fernix.tags.Picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonPictureTest {

    private fun picture(type: Int, description: String = "") = Picture(type, "image/png", description, ByteArray(0))

    @Test
    fun onlyRoleTypesMapToKeys_coverAndOthersNever() {
        assertEquals("ARTIST", PersonPicture.keyOf(7))
        assertEquals("ARTIST", PersonPicture.keyOf(8))
        assertEquals("CONDUCTOR", PersonPicture.keyOf(9))
        assertEquals("ARTIST", PersonPicture.keyOf(10))
        assertEquals("COMPOSER", PersonPicture.keyOf(11))
        assertEquals("LYRICIST", PersonPicture.keyOf(12))
        for (type in listOf(0, 3, 4, 6, 13, 20)) assertNull("type $type", PersonPicture.keyOf(type))
    }

    @Test
    fun aDescriptionEqualToTheName_matches_ignoringCase_evenAmongSeveralPeople() {
        assertTrue(PersonPicture.matches(picture(11, "johnson"), "Johnson", listOf("Johnson", "Other")))
        assertFalse(PersonPicture.matches(picture(11, "Someone else"), "Johnson", listOf("Johnson")))
    }

    @Test
    fun withoutADescription_theTrackMustHaveOneValueUnderTheKey_andItIsThePerson() {
        assertTrue(PersonPicture.matches(picture(8), "Ann", listOf("ann")))
        // Two people share the key and the picture says nothing: ignored rather than guessed.
        assertFalse(PersonPicture.matches(picture(8), "Ann", listOf("Ann", "Bob")))
        assertFalse(PersonPicture.matches(picture(8), "Ann", emptyList()))
        assertFalse(PersonPicture.matches(picture(8), "Ann", listOf("Bob")))
    }

    @Test
    fun aCoverIsNeverAPersonsImage_evenWithTheirNameAsDescription() {
        assertFalse(PersonPicture.matches(picture(3, "Ann"), "Ann", listOf("Ann")))
    }
}
