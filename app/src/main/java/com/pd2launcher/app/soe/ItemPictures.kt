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

    private val skillIcons = HashMap<Int, Bitmap?>()
    private val iconFiles = HashMap<String, ByteArray?>()
    private var skillTables: Pair<ByteArray, ByteArray>? = null
    private var skillTablesRead = false

    /**
     * Skill [id]'s icon, as the game shows it: `skills.bin` gives the skill's class (+ 0x0C; 255
     * for a skill of no class) and its row in `skilldesc.bin` (+ 0x194), whose + 0x07 is the frame
     * of the class's `*Skillicon.dc6` (`Skillicon.dc6` for no class). 1.13c layouts: 0x23C and
     * 0x120 bytes a row, after a DWORD count.
     */
    fun skillIcon(id: Int): Bitmap? {
        if (id < 0) return null
        synchronized(skillIcons) { if (skillIcons.containsKey(id)) return skillIcons[id] }
        val icon = try {
            val (skills, descs) = synchronized(skillIcons) {
                if (!skillTablesRead) {
                    skillTablesRead = true
                    val sk = archives.read("data\\global\\excel\\skills.bin")
                    val sd = archives.read("data\\global\\excel\\skilldesc.bin")
                    skillTables = if (sk != null && sd != null) sk to sd else null
                }
                skillTables
            } ?: return null
            val sb = ByteBuffer.wrap(skills).order(ByteOrder.LITTLE_ENDIAN)
            val db = ByteBuffer.wrap(descs).order(ByteOrder.LITTLE_ENDIAN)
            if (id >= sb.getInt(0)) return null
            val rec = 4 + id * SKILL_ROW
            val cls = skills[rec + 0x0C].toInt() and 0xFF
            val desc = sb.getShort(rec + 0x194).toInt() and 0xFFFF
            if (desc >= db.getInt(0)) return null
            val cel = descs[4 + desc * DESC_ROW + 7].toInt() and 0xFF
            val file = "data\\global\\ui\\SPELLS\\${CLASS_ICONS.getOrElse(cls) { "" }}Skillicon.dc6"
            val raw = synchronized(iconFiles) { iconFiles.getOrPut(file) { archives.read(file) } } ?: return null
            frame(raw, cel, palette)?.first
        } catch (e: Exception) {
            Log.w(TAG, "skill icon $id", e)
            null
        }
        synchronized(skillIcons) { skillIcons[id] = icon }
        return icon
    }

    private val badgeFrames = HashMap<Int, Bitmap?>()

    /** PD2's controller button picture [index] (A 0, B 1, Y 2, X 3, LB 8, RB 9, LT 12, RT 13), 20 x 20. */
    fun badge(index: Int): Bitmap? {
        synchronized(badgeFrames) { if (badgeFrames.containsKey(index)) return badgeFrames[index] }
        val b = try {
            archives.read("data\\global\\ui\\CONTROLS\\Controls_Xbox_20.dc6")?.let { frame(it, index, palette)?.first }
        } catch (_: Exception) { null }
        synchronized(badgeFrames) { badgeFrames[index] = b }
        return b
    }

    /** The resolution add-on's stone (its interface background), first frame; null without it. */
    val stone: Bitmap? by lazy {
        try {
            archives.read("data\\SGD2FreeResolution\\ui\\panel\\D2MRStoneBack.dc6")?.let { frame(it, 0, palette)?.first }
        } catch (_: Exception) { null }
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
        private const val SKILL_ROW = 0x23C
        private const val DESC_ROW = 0x120

        /** The skill icon files' prefixes by class: Amazon, Sorceress, Necromancer, Paladin, Barbarian, Druid, Assassin. */
        private val CLASS_ICONS = listOf("Am", "So", "Ne", "Pa", "Ba", "Dr", "As")

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
