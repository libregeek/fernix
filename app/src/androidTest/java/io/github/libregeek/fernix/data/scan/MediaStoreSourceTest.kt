// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Inserts the app's own audio files into MediaStore (no permission needed) and reads them back. */
@RunWith(AndroidJUnit4::class)
class MediaStoreSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source = MediaStoreSource(context)
    private val inserted = mutableListOf<Uri>()

    @Before
    fun insertFixtures() {
        val sample = InstrumentationRegistry.getInstrumentation().context.assets.open("sample.mp3").use { it.readBytes() }
        for (name in listOf("fernix-test-a.mp3", "fernix-test-b.mp3")) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/FernixTest/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            assertNotNull(uri)
            context.contentResolver.openOutputStream(uri!!)!!.use { it.write(sample) }
            // Clearing IS_PENDING makes MediaStore scan the file, which fills in SIZE.
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            inserted += uri
        }
    }

    @After
    fun cleanUp() {
        inserted.forEach { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun primaryVolume_isMounted() {
        assertTrue(MediaStore.VOLUME_EXTERNAL_PRIMARY in source.mountedVolumes())
    }

    @Test
    fun query_returnsRowsAscendingById_withFolderPath() {
        val rows = source.query(setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)).use { it.asSequence().toList() }
        assertEquals(rows.map { it.mediaStoreId }.sorted(), rows.map { it.mediaStoreId })
        val ours = rows.filter { it.displayName.startsWith("fernix-test-") }
        assertEquals(2, ours.size)
        ours.forEach {
            assertEquals("external_primary/Music/FernixTest", it.folderPath)
            assertTrue(it.sizeBytes > 0)
        }
    }

    @Test
    fun query_noVolumes_isEmpty() {
        source.query(emptySet()).use { assertTrue(!it.hasNext()) }
    }

    @Test
    fun query_unknownVolume_isEmpty() {
        source.query(setOf("no-such-volume")).use { assertTrue(!it.hasNext()) }
    }
}
