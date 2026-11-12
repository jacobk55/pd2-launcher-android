package com.pd2launcher.app.soe

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The running game's own numbers, read from its memory: the game (Wine's `Game.exe`) runs as the
 * app's own Linux user, so the app can read `/proc/<pid>/mem` like any of its files. Only reads;
 * nothing is ever written. Addresses are Diablo II 1.13c's, which Project Diablo 2 is built on,
 * and were checked on an AYN Thor:
 *
 * - D2Client.dll + 0x11BBFC: the player unit; unit + 0x5C its stat list; the list's full stats
 *   (items included) at + 0x48 (array) and + 0x4C (count), 8 bytes each: WORD layer, WORD stat,
 *   DWORD value. Stats 6/7 life/max life and 8/9 mana/max mana are fixed point (value >> 8),
 *   12 the level, 13 the experience.
 * - The experience table (experience.txt as loaded): 32-byte rows, the first DWORD of each the
 *   experience a level starts at; found once by its well-known first values (0, 500, 1500, 3750).
 *
 * Any failure (no game yet, a loading screen, another version) reads as null: the HUD falls back
 * to what it sees in the picture.
 */
class GameMemory private constructor(
    private val pid: Int,
    private val client: Long,
    /** The Diablo II folder the game runs from (its archives are there), when known. */
    val gameFolder: File?,
) {
    private val mem = RandomAccessFile("/proc/$pid/mem", "r")
    @Volatile private var expTable: LongArray? = null
    @Volatile private var itemsTxt: Long = 0L
    @Volatile private var itemTypesTxt: Long = 0L

    @Volatile private var closed = false

    // The tables are looked for away from the HUD's frames (a search reads the game's writable
    // memory and can take a while), once a character is in the game (the menus have not loaded
    // them yet), again every few seconds until found.
    init {
        Thread({
            while (!closed && (expTable == null || itemsTxt == 0L)) {
                try {
                    if (u32(client + PLAYER_UNIT) != 0L) {
                        if (expTable == null) {
                            expTable = findExperienceTable()
                            Log.i(TAG, "experience table: ${expTable?.take(5)}")
                        }
                        if (itemsTxt == 0L) {
                            itemsTxt = findItemsTxt()
                            Log.i(TAG, "item table at ${itemsTxt.toString(16)}")
                            if (itemsTxt != 0L) {
                                itemTypesTxt = findItemTypesTxt(itemsTxt)
                                Log.i(TAG, "item type table at ${itemTypesTxt.toString(16)}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "table search", e)
                }
                if (expTable == null || itemsTxt == 0L) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }, "pd2-gamememory-search").apply { isDaemon = true }.start()
    }

    /** An item in the inventory: its grid place and size, and its picture's file name. */
    data class Item(val x: Int, val y: Int, val width: Int, val height: Int, val code: String, val picture: String)

    /**
     * The player's inventory (page 0), from its item list: unit + 0x60 the inventory, + 0x0C its
     * first item; an item's + 0x04 its row in the item table (0x1A8-byte records: + 0x20 the
     * picture, + 0x40 a unique's, + 0x60 a set item's, + 0x80 the code, + 0x10F/0x110 the size),
     * + 0x14 its data (+ 0x00 the quality, + 0x45 the page, + 0x64 the next item), + 0x2C its
     * grid place (+ 0x0C x, + 0x10 y). Null until the item table is found.
     */
    fun inventory(): List<Item>? = try {
        val table = itemsTxt
        val player = u32(client + PLAYER_UNIT)
        if (table == 0L || player == 0L) null else {
            val inv = u32(player + 0x60)
            val out = ArrayList<Item>()
            var item = if (inv == 0L) 0L else u32(inv + 0x0C)
            var guard = 0
            while (item != 0L && guard++ < 512) {
                val txt = u32(item + 4)
                val data = u32(item + 0x14)
                if (data == 0L) break
                val page = readBytes(data + 0x45, 1)[0].toInt() and 0xFF
                if (page == 0 && txt < 4096) {
                    val rec = readBytes(table + txt * ITEM_ROW, ITEM_ROW.toInt())
                    val quality = u32(data)
                    val path = u32(item + 0x2C)
                    val base = variant(rec, readBytes(data + 0x49, 1)[0].toInt() and 0xFF) ?: cstr(rec, 0x20)
                    val special = when (quality) {
                        7L -> cstr(rec, 0x40)
                        5L -> cstr(rec, 0x60)
                        else -> ""
                    }
                    out += Item(
                        x = u32(path + 0x0C).toInt(), y = u32(path + 0x10).toInt(),
                        width = rec[0x10F].toInt() and 0xFF, height = rec[0x110].toInt() and 0xFF,
                        code = String(rec, 0x80, 4, Charsets.ISO_8859_1).trim(),
                        picture = special.ifEmpty { base },
                    )
                }
                item = u32(data + 0x64)
            }
            out
        }
    } catch (e: Exception) {
        null
    }

    private fun cstr(b: ByteArray, at: Int): String {
        var end = at
        while (end < at + 32 && b[end] != 0.toByte()) end++
        return String(b, at, end - at, Charsets.ISO_8859_1)
    }

    /**
     * The picture variant an item of [rec]'s type shows (charms, rings, amulets, jewels have
     * several; the item keeps which at its data + 0x49), or null when its type has none: the
     * type (a WORD at + 0x11E) indexes the item type table, whose 0xE4-byte records list up to
     * six picture names of 32 bytes from + 0x24.
     */
    private fun variant(rec: ByteArray, index: Int): String? {
        val types = itemTypesTxt
        if (types == 0L) return null
        val type = (rec[0x11E].toInt() and 0xFF) or ((rec[0x11F].toInt() and 0xFF) shl 8)
        if (type >= 1024) return null
        val t = readBytes(types + type * TYPE_ROW + 0x24, 6 * 32)
        val names = (0 until 6).map { cstr(t, it * 32) }.filter { it.isNotEmpty() }
        return if (names.isEmpty()) null else names[index % names.size]
    }

    /**
     * The item type table: the small charm's type ("scha", its pictures "invch1" ...) is the
     * charm item "cm1"'s type, so the table starts that many records before it.
     */
    private fun findItemTypesTxt(items: Long): Long {
        var cm1Type = -1
        for (i in 0 until 2048) {
            val code = readBytes(items + i * ITEM_ROW + 0x80, 4)
            if (code[0] == 0.toByte()) break
            if (String(code, Charsets.ISO_8859_1) == "cm1 ") {
                val w = readBytes(items + i * ITEM_ROW + 0x11E, 2)
                cm1Type = (w[0].toInt() and 0xFF) or ((w[1].toInt() and 0xFF) shl 8)
                break
            }
        }
        if (cm1Type < 0) return 0L
        val needle = byteArrayOf('i'.code.toByte(), 'n'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), 'h'.code.toByte(), '1'.code.toByte(), 0)
        val scha = "scha".toByteArray(Charsets.ISO_8859_1)
        val hit = scan(needle) { at -> readBytes(at - 0x24, 4).contentEquals(scha) } ?: return 0L
        return hit - 0x24 - cm1Type * TYPE_ROW
    }

    /** The item table: found by its minor healing potion's record ("invhp1" and code "hp1 "). */
    private fun findItemsTxt(): Long {
        val needle = byteArrayOf('i'.code.toByte(), 'n'.code.toByte(), 'v'.code.toByte(), 'h'.code.toByte(), 'p'.code.toByte(), '1'.code.toByte(), 0)
        val code = "hp1 ".toByteArray(Charsets.ISO_8859_1)
        val hit = scan(needle) { at -> readBytes(at + 0x60, 4).contentEquals(code) } ?: return 0L
        var start = hit - 0x20
        fun plausible(rec: Long) = try {
            val b = readBytes(rec + 0x20, 4)
            b[0] == 'i'.code.toByte() && b[1] == 'n'.code.toByte() && b[2] == 'v'.code.toByte()
        } catch (_: Exception) { false }
        while (plausible(start - ITEM_ROW)) start -= ITEM_ROW
        return start
    }

    data class Stats(
        val life: Int, val maxLife: Int, val mana: Int, val maxMana: Int, val level: Int, val experience: Long,
        /** Every base stat by its id, as the game holds it (items included). */
        val all: Map<Int, Long> = emptyMap(),
    ) {
        /** How far into the current level, 0 to 1; null without the experience table. */
        var levelProgress: Float? = null
    }

    /**
     * The explored automap of the player's area: its cells, three numbers each (the cell's picture,
     * a frame of the game's `MaxiMap.dc6`, and its x and y in automap pixels), floors first, then
     * walls, objects and the rest, as the game draws them; and the player's position in the same
     * pixels.
     */
    class Automap(val layer: Int, val cells: IntArray) {
        var playerX = 0f
        var playerY = 0f
    }

    private var automap: Automap? = null
    private var automapRead = 0L

    /**
     * The automap: the cells read again at most once a second (they only grow as the player
     * explores), the player's position every call. D2Client + 0x11C1C0 holds the current layer in
     * this build (0x11CF28 in plain 1.13c): {no, saved, floors, walls, objects, extras, next}, each
     * a tree of cells {saved, WORD cell, short x, short y, WORD weight, less, more}.
     */
    fun automap(now: Long): Automap? = try {
        val player = u32(client + PLAYER_UNIT)
        if (player == 0L) null else {
            var map = automap
            if (map == null || now - automapRead > 1_000_000_000L) {
                automapRead = now
                val layer = listOf(AUTOMAP_LAYER, AUTOMAP_LAYER_113C).map { u32(client + it) }.firstOrNull { it != 0L }
                map = if (layer == null) null else {
                    val head = read(layer, 28)
                    val out = ArrayList<Int>()
                    for (tree in intArrayOf(8, 12, 16, 20)) cells(head.getInt(tree).toLong() and 0xFFFFFFFFL, out)
                    Automap(head.getInt(0), out.toIntArray())
                }
                automap = map
            }
            map?.also {
                val path = u32(player + 0x2C)
                val x = u16(path + 0x02).toFloat()
                val y = u16(path + 0x06).toFloat()
                it.playerX = (x - y) * 1.6f
                it.playerY = (x + y) * 0.8f
            }
        }
    } catch (e: Exception) {
        null
    }

    /** A cell tree's cells into [out]: picture, x, y each. */
    private fun cells(root: Long, out: ArrayList<Int>) {
        val stack = ArrayDeque<Long>()
        if (root != 0L) stack.addLast(root)
        while (stack.isNotEmpty() && out.size < MAX_CELLS * 3) {
            val c = read(stack.removeLast(), 20)
            out += c.getShort(4).toInt() and 0xFFFF
            out += c.getShort(6).toInt()
            out += c.getShort(8).toInt()
            val less = c.getInt(12).toLong() and 0xFFFFFFFFL
            val more = c.getInt(16).toLong() and 0xFFFFFFFFL
            if (less != 0L) stack.addLast(less)
            if (more != 0L) stack.addLast(more)
        }
    }

    fun close() {
        closed = true
        try { mem.close() } catch (_: Exception) {}
    }

    /** Whether the game process is still there. */
    val alive: Boolean get() = File("/proc/$pid").exists()

    fun stats(): Stats? = try {
        val player = u32(client + PLAYER_UNIT)
        if (player == 0L) null else {
            val list = u32(player + 0x5C)
            val array = u32(list + 0x48)
            val count = u16(list + 0x4C)
            if (list == 0L || array == 0L || count !in 1..1024) null else {
                val raw = read(array, count * 8)
                val values = HashMap<Int, Long>()
                for (i in 0 until count) {
                    val layer = raw.getShort(i * 8).toInt() and 0xFFFF
                    val stat = raw.getShort(i * 8 + 2).toInt() and 0xFFFF
                    if (layer == 0) values[stat] = if (stat in SIGNED) raw.getInt(i * 8 + 4).toLong() else raw.getInt(i * 8 + 4).toLong() and 0xFFFFFFFFL
                }
                val maxLife = ((values[7] ?: 0) shr 8).toInt()
                if (maxLife <= 0) null else Stats(
                    life = ((values[6] ?: 0) shr 8).toInt(),
                    maxLife = maxLife,
                    mana = ((values[8] ?: 0) shr 8).toInt(),
                    maxMana = ((values[9] ?: 0) shr 8).toInt(),
                    level = (values[12] ?: 0).toInt(),
                    experience = values[13] ?: 0,
                    all = values,
                ).also { it.levelProgress = progress(it.level, it.experience) }
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun progress(level: Int, experience: Long): Float? {
        val table = expTable ?: return null
        if (level < 1 || level >= table.size) return if (level >= table.size) 1f else null
        val from = table[level - 1]
        val to = table[level]
        if (to <= from) return 1f
        return ((experience - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    }

    /** The levels' start experience, level 1 first; found in the game's writable memory. */
    private fun findExperienceTable(): LongArray? {
        val pattern = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(7) { putInt(500) } }.array()
        val row500 = scan(pattern) { at -> u32(at - ROW) == 0L && u32(at + ROW) == 1500L } ?: return null
        val levels = ArrayList<Long>()
        var row = row500 - ROW
        while (levels.size < 128) {
            val v = u32(row)
            if (levels.isNotEmpty() && v <= levels.last()) break
            levels += v
            row += ROW
        }
        return levels.toLongArray()
    }

    /** The first place in the game's writable memory holding [pattern] where [check] agrees. */
    private fun scan(pattern: ByteArray, check: (Long) -> Boolean): Long? {
        for ((start, end) in writableRegions()) {
            if (end - start > 64L shl 20) continue
            var at = start
            val chunk = 1 shl 20
            while (at < end) {
                val n = minOf(chunk.toLong() + pattern.size, end - at).toInt()
                val bytes = try { readBytes(at, n) } catch (_: Exception) { break }
                var from = 0
                while (true) {
                    val i = indexOf(bytes, pattern, from)
                    if (i < 0) break
                    if (try { check(at + i) } catch (_: Exception) { false }) return at + i
                    from = i + 1
                }
                at += chunk
            }
        }
        return null
    }

    private fun writableRegions(): List<Pair<Long, Long>> =
        File("/proc/$pid/maps").readLines().mapNotNull { line ->
            val parts = line.split(' ', limit = 6)
            if (parts.size < 5 || !parts[1].startsWith("rw") || parts[4] != "0") return@mapNotNull null
            val (a, b) = parts[0].split('-').map { it.toLong(16) }
            if (b > 0x7FFF0000L) null else a to b
        }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        val first = needle[0]
        outer@ for (i in from..hay.size - needle.size) {
            if (hay[i] != first) continue
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun readBytes(addr: Long, n: Int): ByteArray {
        val b = ByteArray(n)
        synchronized(mem) {
            mem.seek(addr)
            mem.readFully(b)
        }
        return b
    }

    private fun read(addr: Long, n: Int): ByteBuffer = ByteBuffer.wrap(readBytes(addr, n)).order(ByteOrder.LITTLE_ENDIAN)
    private fun u32(addr: Long): Long = read(addr, 4).getInt(0).toLong() and 0xFFFFFFFFL
    private fun u16(addr: Long): Int = read(addr, 2).getShort(0).toInt() and 0xFFFF

    companion object {
        private const val TAG = "pd2-gamememory"
        private const val PLAYER_UNIT = 0x11BBFCL
        private const val AUTOMAP_LAYER = 0x11C1C0L
        private const val AUTOMAP_LAYER_113C = 0x11CF28L
        private const val MAX_CELLS = 30000
        private const val ROW = 32L
        private const val ITEM_ROW = 0x1A8L
        private const val TYPE_ROW = 0xE4L

        /** Stats that can go below zero: the resistances. */
        private val SIGNED = setOf(39, 41, 43, 45)

        /** The app's running game, or null: Game.exe among this user's processes, with D2Client.dll loaded. */
        fun open(): GameMemory? {
            val procs = File("/proc").listFiles { f -> f.name.all(Char::isDigit) } ?: return null
            for (p in procs) {
                val cmd = try { File(p, "cmdline").readText() } catch (_: Exception) { continue }
                if (!cmd.contains("Game.exe", ignoreCase = true)) continue
                val pid = p.name.toInt()
                val line = try {
                    File(p, "maps").useLines { lines -> lines.firstOrNull { it.contains("D2Client.dll", ignoreCase = true) } }
                } catch (_: Exception) { null } ?: continue
                val client = line.substringBefore('-').toLongOrNull(16) ?: continue
                // The DLL's folder (PD2's ProjectD2), and the Diablo II folder around it.
                val dll = line.indexOf('/').takeIf { it >= 0 }?.let { File(line.substring(it).trim()).parentFile }
                val folder = if (dll != null && dll.name.equals("ProjectD2", ignoreCase = true)) dll.parentFile else dll
                return try {
                    GameMemory(pid, client, folder).also { Log.i(TAG, "game pid $pid, D2Client at ${client.toString(16)}") }
                } catch (e: Exception) {
                    Log.w(TAG, "cannot read game $pid", e)
                    null
                }
            }
            return null
        }
    }
}
