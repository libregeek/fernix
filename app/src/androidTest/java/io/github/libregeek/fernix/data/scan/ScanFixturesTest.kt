// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.data.scan

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.tags.TagValues
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end: real files in MediaStore, the real [MediaStoreSource] and [MediaStoreTagSource],
 * one fixture per format (MP3, Ogg Vorbis, Opus, FLAC, WAV). The files are the test app's own,
 * so no storage permission is needed.
 */
@RunWith(AndroidJUnit4::class)
class ScanFixturesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = "Music/FernixScan"
    private val scope = ScanScope.Selected(setOf("external_primary/$folder"))
    private val inserted = mutableListOf<Uri>()
    private lateinit var db: FernixDatabase

    private val fixtures = listOf(
        "sample.mp3" to "audio/mpeg",
        "sample.ogg" to "audio/ogg",
        "sample.opus" to "audio/ogg",
        "sample.flac" to "audio/flac",
        "sample.wav" to "audio/x-wav",
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FernixDatabase::class.java).build()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for ((name, mime) in fixtures) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "fernix-scan-$name")
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "$folder/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            assertNotNull("insert $name", uri)
            assets.open(name).use { input -> context.contentResolver.openOutputStream(uri!!)!!.use { input.copyTo(it) } }
            context.contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            inserted += uri
        }
    }

    @After
    fun tearDown() {
        db.close()
        inserted.forEach { context.contentResolver.delete(it, null, null) }
    }

    private fun scanner() = LibraryScanner(db, MediaStoreSource(context), MediaStoreTagSource(context))

    private fun column(sql: String): List<String> =
        db.openHelper.readableDatabase.query(sql).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    @Test
    fun scansEveryFormat_withItsTags() = runBlocking {
        val scanner = scanner()
        scanner.scan(scope)

        assertEquals(ScanState.Finished(5), scanner.state.value)
        assertEquals(
            setOf("Aadyamayi Kanda Naal", "Thechimandaram Thulassi", "Opus Song", "Ponnum Pathinettam", "Wav Song"),
            column("SELECT title FROM tracks").toSet(),
        )

        val byName = column("SELECT displayName || '|' || artist FROM tracks").toSet()
        assertEquals(
            setOf(
                "fernix-scan-sample.mp3|" + TagValues.join(listOf("K. J. Yesudas", "K. S. Chithra")),
                "fernix-scan-sample.ogg|Vinaya",
                "fernix-scan-sample.opus|Opus Artist",
                "fernix-scan-sample.flac|K. J. Yesudas",
                "fernix-scan-sample.wav|Wav Artist",
            ),
            byName,
        )
        // K. J. Yesudas is credited on two tracks, as one person.
        assertEquals(
            listOf("2"),
            column("SELECT p.trackCount FROM people p JOIN tag_values v ON v.id = p.valueId WHERE v.value = 'K. J. Yesudas'"),
        )
        assertEquals(listOf("external_primary/$folder"), column("SELECT DISTINCT f.path FROM tracks t JOIN folders f ON f.id = t.folderId"))
        // The FLAC's multi-value genre is two rows under one key.
        assertEquals(
            setOf("Asian Music", "Indian Music"),
            column("SELECT v.value FROM key_values kv JOIN tag_keys k ON k.id = kv.keyId JOIN tag_values v ON v.id = kv.valueId WHERE k.`key` = 'GENRE' AND v.value LIKE '%Music'").toSet(),
        )
    }

    @Test
    fun rescan_ofUnchangedFiles_changesNothing() = runBlocking {
        scanner().scan(scope)
        val again = scanner()
        again.scan(scope)
        assertEquals(ScanState.Finished(0), again.state.value)
        assertEquals(listOf("5"), column("SELECT COUNT(*) FROM tracks"))
    }

    @Test
    fun deletedFile_isRemovedOnNextScan() = runBlocking {
        scanner().scan(scope)
        context.contentResolver.delete(inserted.removeAt(0), null, null)

        val again = scanner()
        again.scan(scope)

        assertEquals(ScanState.Finished(1), again.state.value)
        assertEquals(listOf("4"), column("SELECT COUNT(*) FROM tracks"))
    }
}
