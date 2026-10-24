// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Makes the small JPEG kept in the [ThumbnailCache] from a full-size embedded picture. */
object Thumbnails {

    const val MAX_SIDE = 160
    private const val QUALITY = 85

    /**
     * [picture] (any format Android decodes) scaled to fit within [MAX_SIDE] pixels, on white (a
     * transparent PNG would otherwise turn black in a JPEG), as JPEG bytes; null if it can't be decoded.
     * The picture is decoded at a reduced size, so a huge cover doesn't cost its full memory.
     */
    fun encode(picture: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            picture, 0, picture.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val width = max(1, (decoded.width * minOf(scale, 1f)).toInt())
        val height = max(1, (decoded.height * minOf(scale, 1f)).toInt())
        val scaled = if (width == decoded.width && height == decoded.height) decoded
        else decoded.scale(width, height)

        val flat = createBitmap(width, height)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        flat.recycle()
        return out.toByteArray()
    }
}
