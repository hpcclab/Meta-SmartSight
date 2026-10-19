package edu.unt.smartsight.metaglassestest

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Decoded glasses frames are tightly packed I420: one Y plane of [width] * [height],
 * then U and V planes of half that size in each dimension.
 */
internal object I420Frames {
    fun argb(buffer: ByteBuffer, width: Int, height: Int): IntArray? {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            return null
        }
        val ySize = width * height
        val chromaSize = ySize / 4
        val source = buffer.duplicate()
        source.rewind()
        if (source.remaining() < ySize + chromaSize * 2) {
            return null
        }
        val y = ByteArray(ySize)
        val u = ByteArray(chromaSize)
        val v = ByteArray(chromaSize)
        source.get(y)
        source.get(u)
        source.get(v)
        val pixels = IntArray(ySize)
        val chromaWidth = width / 2
        var index = 0
        for (row in 0 until height) {
            val chromaRow = (row / 2) * chromaWidth
            for (col in 0 until width) {
                val chromaIndex = chromaRow + col / 2
                pixels[index] = limitedRangeArgb(
                    y[index].toInt() and 0xFF,
                    u[chromaIndex].toInt() and 0xFF,
                    v[chromaIndex].toInt() and 0xFF,
                )
                index++
            }
        }
        return pixels
    }

    fun toBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap? {
        val pixels = argb(buffer, width, height) ?: return null
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun limitedRangeArgb(y: Int, u: Int, v: Int): Int {
        val c = (y - 16).coerceAtLeast(0)
        val d = u - 128
        val e = v - 128
        val r = (298 * c + 409 * e + 128) shr 8
        val g = (298 * c - 100 * d - 208 * e + 128) shr 8
        val b = (298 * c + 516 * d + 128) shr 8
        return (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
    }
}
