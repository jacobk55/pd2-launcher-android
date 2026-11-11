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
class GameMemory private constructor(private val pid: Int, private val client: Long) {
    private val mem = RandomAccessFile("/proc/$pid/mem", "r")
    private var expTable: LongArray? = null
    private var expSearched = false

    data class Stats(val life: Int, val maxLife: Int, val mana: Int, val maxMana: Int, val level: Int, val experience: Long) {
        /** How far into the current level, 0 to 1; null without the experience table. */
        var levelProgress: Float? = null
    }

    fun close() = try { mem.close() } catch (_: Exception) {}

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
                    if (layer == 0) values[stat] = raw.getInt(i * 8 + 4).toLong() and 0xFFFFFFFFL
                }
                val maxLife = ((values[7] ?: 0) shr 8).toInt()
                if (maxLife <= 0) null else Stats(
                    life = ((values[6] ?: 0) shr 8).toInt(),
                    maxLife = maxLife,
                    mana = ((values[8] ?: 0) shr 8).toInt(),
                    maxMana = ((values[9] ?: 0) shr 8).toInt(),
                    level = (values[12] ?: 0).toInt(),
                    experience = values[13] ?: 0,
                ).also { it.levelProgress = progress(it.level, it.experience) }
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun progress(level: Int, experience: Long): Float? {
        if (!expSearched) {
            expSearched = true
            expTable = findExperienceTable()
            Log.i(TAG, "experience table: ${expTable?.take(5)}")
        }
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
        for ((start, end) in writableRegions()) {
            if (end - start > 64L shl 20) continue
            var at = start
            val chunk = 1 shl 20
            while (at < end) {
                val n = minOf(chunk.toLong() + 32, end - at).toInt()
                val bytes = try { readBytes(at, n) } catch (_: Exception) { break }
                val i = indexOf(bytes, pattern)
                if (i >= 0) {
                    val row500 = at + i
                    val first = row500 - ROW
                    if (u32(first) == 0L && u32(row500 + ROW) == 1500L) {
                        val levels = ArrayList<Long>()
                        var row = first
                        while (levels.size < 128) {
                            val v = u32(row)
                            if (levels.isNotEmpty() && v <= levels.last()) break
                            levels += v
                            row += ROW
                        }
                        return levels.toLongArray()
                    }
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

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
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
        private const val ROW = 32L

        /** The app's running game, or null: Game.exe among this user's processes, with D2Client.dll loaded. */
        fun open(): GameMemory? {
            val procs = File("/proc").listFiles { f -> f.name.all(Char::isDigit) } ?: return null
            for (p in procs) {
                val cmd = try { File(p, "cmdline").readText() } catch (_: Exception) { continue }
                if (!cmd.contains("Game.exe", ignoreCase = true)) continue
                val pid = p.name.toInt()
                val client = try {
                    File(p, "maps").useLines { lines ->
                        lines.firstOrNull { it.contains("D2Client.dll", ignoreCase = true) }?.substringBefore('-')?.toLong(16)
                    }
                } catch (_: Exception) { null } ?: continue
                return try {
                    GameMemory(pid, client).also { Log.i(TAG, "game pid $pid, D2Client at ${client.toString(16)}") }
                } catch (e: Exception) {
                    Log.w(TAG, "cannot read game $pid", e)
                    null
                }
            }
            return null
        }
    }
}
