// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.tags

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Random
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A damaged file must come back as "unreadable" (or with whatever could be read), never crash the
 * process: one bad file must not stop a scan (spec/architecture.md §6 *Errors*). Each sample is cut short
 * at several places and has bytes flipped, and each reader is run on the result; surviving is the test.
 */
@RunWith(AndroidJUnit4::class)
class CorruptFilesTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val samples = listOf("sample.mp3", "sample.ogg", "sample.flac", "sample.m4a", "sample.opus", "sample.wav")

    private fun original(asset: String): ByteArray = instrumentation.context.assets.open(asset).use { it.readBytes() }

    /** Runs all three readers on [bytes] written to a real file, like a MediaStore descriptor. */
    private fun readAll(bytes: ByteArray) {
        val file = File(instrumentation.targetContext.cacheDir, "corrupt.bin")
        try {
            file.writeBytes(bytes)
            fun open() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            TagReader.readProperties(open())
            TagReader.readAudioProperties(open())
            TagReader.readPictures(open())
        } finally {
            file.delete()
        }
    }

    @Test
    fun truncatedFiles_neverCrashTheReaders() {
        for (asset in samples) {
            val bytes = original(asset)
            for (length in listOf(0, 1, 3, 10, 64, 200, 1000, bytes.size / 2, bytes.size - 1)) {
                readAll(bytes.copyOf(length.coerceIn(0, bytes.size)))
            }
        }
    }

    @Test
    fun filesWithFlippedBytes_neverCrashTheReaders() {
        val random = Random(42)
        for (asset in samples) {
            val bytes = original(asset)
            repeat(12) {
                val damaged = bytes.copyOf()
                repeat(1 + damaged.size / 200) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
                readAll(damaged)
            }
        }
    }

    @Test
    fun garbageAndEmptyFiles_areUnreadable_notFatal() {
        readAll(ByteArray(0))
        readAll(ByteArray(4096) { 0 })
        readAll(ByteArray(4096) { 0xFF.toByte() })
        readAll(ByteArray(100_000).also { Random(7).nextBytes(it) })
        // An ID3 header that promises far more than the file holds.
        readAll(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F, 1, 2, 3))
    }

    @Test
    fun aDescriptorThatIsNotAFile_isNull_notFatal() {
        val (read, write) = ParcelFileDescriptor.createPipe()
        write.close() // nothing to read, and a pipe cannot seek
        assertNull(TagReader.readProperties(read))
        assertTrue(true)
    }
}
