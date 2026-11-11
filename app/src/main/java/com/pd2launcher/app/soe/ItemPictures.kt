package com.pd2launcher.app.soe

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The items' inventory pictures, decoded from the player's own game archives ([GameArchives]):
 * `data\global\items\<name>.dc6`, first frame, in the units palette. Pictures are decoded once
 * and kept; a name the archives do not have is remembered as missing.
 */
internal class ItemPictures private constructor(private val archives: GameArchives, private val palette: IntArray) {
    private val cache = HashMap<String, Bitmap?>()

    /** The picture called [name] (as the item table gives it, without folder or extension). */
    fun get(name: String): Bitmap? {
        if (name.isEmpty()) return null
        val key = name.lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val picture = try {
            archives.read("data\\global\\items\\$key.dc6")?.let { decode(it) }
        } catch (e: Exception) {
            Log.w(TAG, "picture $key", e)
            null
        }
        synchronized(cache) { cache[key] = picture }
        return picture
    }

    /** A DC6 file's first frame: rows from the bottom up; 0x80 ends a row, 0x80 | n skips n. */
    private fun decode(raw: ByteArray): Bitmap? {
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != 6) return null
        val frame = b.getInt(24)
        val w = b.getInt(frame + 4)
        val h = b.getInt(frame + 8)
        val length = b.getInt(frame + 28)
        if (w !in 1..512 || h !in 1..512) return null
        val pixels = IntArray(w * h)
        var p = frame + 32
        val end = minOf(p + length, raw.size)
        var x = 0
        var y = h - 1
        while (p < end) {
            val c = raw[p++].toInt() and 0xFF
            when {
                c == 0x80 -> { x = 0; y-- }
                c and 0x80 != 0 -> x += c and 0x7F
                else -> repeat(c) {
                    if (p < raw.size) {
                        if (x in 0 until w && y in 0 until h) pixels[y * w + x] = palette[raw[p].toInt() and 0xFF]
                        p++
                        x++
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val TAG = "pd2-itempictures"

        /** The pictures from the game in [gameFolder], or null when its archives cannot be read. */
        fun open(gameFolder: File): ItemPictures? {
            val archives = GameArchives.open(gameFolder) ?: return null
            val pal = archives.read("data\\global\\palette\\units\\pal.dat")
            if (pal == null || pal.size < 768) {
                archives.close()
                return null
            }
            // BGR triplets; index 0 is clear.
            val palette = IntArray(256) { i ->
                if (i == 0) 0 else (0xFF shl 24) or ((pal[i * 3 + 2].toInt() and 0xFF) shl 16) or
                    ((pal[i * 3 + 1].toInt() and 0xFF) shl 8) or (pal[i * 3].toInt() and 0xFF)
            }
            Log.i(TAG, "archives in $gameFolder")
            return ItemPictures(archives, palette)
        }
    }
}
