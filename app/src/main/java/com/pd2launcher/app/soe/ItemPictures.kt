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

    /** A frame of the game's automap pictures, and where its top left sits against its cell. */
    class Cell(val bitmap: Bitmap, val dx: Int, val dy: Int)

    private val cells = HashMap<Int, Cell?>()
    private var maxiMap: ByteArray? = null
    private var maxiMapRead = false

    /** The automap's palette: the units palette, brighter, as the game's map reads over the scene. */
    private val mapPalette = IntArray(256) { i ->
        val c = palette[i]
        if (c == 0) 0 else {
            fun up(v: Int) = minOf(255, (v * 17) / 10)
            (0xFF shl 24) or (up((c shr 16) and 0xFF) shl 16) or (up((c shr 8) and 0xFF) shl 8) or up(c and 0xFF)
        }
    }

    /**
     * The automap cell picture [n]: frame [n] of `data\global\ui\automap\MaxiMap.dc6`, by the
     * cell numbers the game's automap keeps. Null when there is no such frame.
     */
    fun automapCell(n: Int): Cell? {
        synchronized(cells) { if (cells.containsKey(n)) return cells[n] }
        val raw = synchronized(cells) {
            if (!maxiMapRead) {
                maxiMapRead = true
                maxiMap = try { archives.read("data\\global\\ui\\automap\\MaxiMap.dc6") } catch (_: Exception) { null }
            }
            maxiMap
        }
        val cell = raw?.let {
            try { frame(it, n, mapPalette)?.let { (bitmap, dx, dy) -> Cell(bitmap, dx, dy - bitmap.height) } } catch (_: Exception) { null }
        }
        synchronized(cells) { cells[n] = cell }
        return cell
    }

    /** The picture called [name] (as the item table gives it, without folder or extension). */
    fun get(name: String): Bitmap? {
        if (name.isEmpty()) return null
        val key = name.lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val picture = try {
            archives.read("data\\global\\items\\$key.dc6")?.let { frame(it, 0, palette)?.first }
        } catch (e: Exception) {
            Log.w(TAG, "picture $key", e)
            null
        }
        synchronized(cache) { cache[key] = picture }
        return picture
    }

    /**
     * Frame [index] of a DC6 file (its first direction) in [palette], with its x and y offsets:
     * rows from the bottom up; 0x80 ends a row, 0x80 | n skips n.
     */
    private fun frame(raw: ByteArray, index: Int, palette: IntArray): Triple<Bitmap, Int, Int>? {
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != 6) return null
        val frames = b.getInt(16) * b.getInt(20)
        if (index !in 0 until frames) return null
        val frame = b.getInt(24 + index * 4)
        val w = b.getInt(frame + 4)
        val h = b.getInt(frame + 8)
        val dx = b.getInt(frame + 12)
        val dy = b.getInt(frame + 16)
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
        return Triple(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888), dx, dy)
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
