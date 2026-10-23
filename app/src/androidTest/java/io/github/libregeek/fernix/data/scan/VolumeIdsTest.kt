// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec/architecture.md §5 keys `tracks` by MediaStore `_ID` alone, which is only safe if ids
 * never repeat across volumes. Needs a second mounted volume (an emulated SD card; see
 * docs/DEVELOPMENT_SETUP_FEDORA.md), otherwise it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class VolumeIdsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver
    private val inserted = mutableListOf<Uri>()

    @After
    fun cleanUp() {
        inserted.forEach { resolver.delete(it, null, null) }
    }

    private fun insert(volume: String, name: String): Uri {
        val sample = InstrumentationRegistry.getInstrumentation().context.assets.open("sample.ogg").use { it.readBytes() }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/FernixVolumeIds/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(volume), values)
        assertNotNull("insert $name on $volume", uri)
        resolver.openOutputStream(uri!!)!!.use { it.write(sample) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        inserted += uri
        return uri
    }

    @Test
    fun idsDoNotCollideAcrossVolumes_andTheExternalUriFindsTheRightFile() {
        val volumes = MediaStore.getExternalVolumeNames(context)
        val secondary = volumes.firstOrNull { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
        assumeTrue("No second volume mounted: $volumes", secondary != null)

        // Interleave inserts so each volume's counter has a chance to hand out the same id.
        val expected = HashMap<Long, String>() // id -> display name
        repeat(5) { i ->
            for (volume in listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY, secondary!!)) {
                val name = "fernix-vol-$volume-$i.ogg"
                val id = ContentUris.parseId(insert(volume, name))
                assertTrue("id $id handed out twice (second time for $name, first for ${expected[id]})", expected.put(id, name) == null)
            }
        }
        assertEquals(10, expected.size)

        // The app builds EXTERNAL_CONTENT_URI + id; it must resolve to the file that owns the id.
        for ((id, name) in expected) {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null).use { c ->
                assertTrue("no row for $uri", c != null && c.moveToFirst())
                assertEquals(name, c!!.getString(0))
            }
        }
    }
}
