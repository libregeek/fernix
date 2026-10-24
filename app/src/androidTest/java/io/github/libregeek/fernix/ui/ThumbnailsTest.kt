// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.libregeek.fernix.ui.art.Thumbnails
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailsTest {

    private fun png(width: Int, height: Int, color: Int, transparent: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (!transparent) bitmap.eraseColor(color)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun decode(jpeg: ByteArray) = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)!!

    @Test
    fun aLargeCover_isScaledToFitWithinTheMaximumSide_keepingItsShape_asASmallJpeg() {
        val jpeg = Thumbnails.encode(png(1600, 800, Color.RED))!!
        val thumb = decode(jpeg)
        assertEquals(Thumbnails.MAX_SIDE, thumb.width)
        assertEquals(Thumbnails.MAX_SIDE / 2, thumb.height)
        assertTrue("size ${jpeg.size}", jpeg.size < 20_000)
        assertTrue(Color.red(thumb.getPixel(10, 10)) > 200 && Color.green(thumb.getPixel(10, 10)) < 60)
    }

    @Test
    fun aSmallPicture_isNotEnlarged() {
        val thumb = decode(Thumbnails.encode(png(4, 4, Color.BLUE))!!)
        assertEquals(4, thumb.width)
        assertEquals(4, thumb.height)
    }

    @Test
    fun transparency_becomesWhite_notBlack() {
        val thumb = decode(Thumbnails.encode(png(40, 40, Color.TRANSPARENT, transparent = true))!!)
        assertTrue(Color.red(thumb.getPixel(5, 5)) > 240)
    }

    @Test
    fun somethingThatIsNotAPicture_givesNull() {
        assertNull(Thumbnails.encode(ByteArray(100) { 7 }))
        assertNull(Thumbnails.encode(ByteArray(0)))
    }
}
