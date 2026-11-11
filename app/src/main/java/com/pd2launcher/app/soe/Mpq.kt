package com.pd2launcher.app.soe

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Reads files out of one of the game's MPQ archives (version 1, as Diablo II's are): the hash and
 * block tables, encrypted or not, in sectors or whole, stored, PKWARE-imploded or zlib-compressed.
 * Only reads; used for the inventory's item pictures, from the player's own game files.
 */
internal class Mpq(file: File) : AutoCloseable {
    private val f = RandomAccessFile(file, "r")
    private val base: Long
    private val sectorSize: Int
    private val hashes: IntArray
    private val blocks: IntArray

    init {
        var at = 0L
        while (true) {
            f.seek(at)
            if (f.readInt() == 0x4D50511A) break // "MPQ\x1A"
            at += 512
            if (at > f.length()) throw IllegalArgumentException("not an MPQ: $file")
        }
        base = at
        val head = bytes(base, 32)
        sectorSize = 512 shl (head.getShort(14).toInt() and 0xFFFF)
        val hashPos = head.getInt(16).toLong() and 0xFFFFFFFFL
        val blockPos = head.getInt(20).toLong() and 0xFFFFFFFFL
        val hashCount = head.getInt(24)
        val blockCount = head.getInt(28)
        hashes = table(base + hashPos, hashCount * 4, hash("(hash table)", 3))
        blocks = table(base + blockPos, blockCount * 4, hash("(block table)", 3))
    }

    private fun table(at: Long, words: Int, key: Int): IntArray {
        val b = bytes(at, words * 4)
        val out = IntArray(words) { b.getInt(it * 4) }
        decrypt(out, key)
        return out
    }

    private fun bytes(at: Long, n: Int): ByteBuffer {
        val a = ByteArray(n)
        synchronized(f) {
            f.seek(at)
            f.readFully(a)
        }
        return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
    }

    /** The file [name] (backslashes), or null when this archive does not have it. */
    fun read(name: String): ByteArray? {
        val n = hashes.size / 4
        if (n == 0) return null
        val a = hash(name, 1)
        val b = hash(name, 2)
        var i = Integer.remainderUnsigned(hash(name, 0), n)
        repeat(n) {
            val block = hashes[i * 4 + 3]
            if (block == -1) return null
            if (hashes[i * 4] == a && hashes[i * 4 + 1] == b && block != -2) return block(name, block)
            i = (i + 1) % n
        }
        return null
    }

    private fun block(name: String, index: Int): ByteArray? {
        val pos = blocks[index * 4].toLong() and 0xFFFFFFFFL
        val packed = blocks[index * 4 + 1]
        val size = blocks[index * 4 + 2]
        val flags = blocks[index * 4 + 3]
        if (flags and EXISTS == 0) return null
        val raw = bytes(base + pos, packed).array()
        var key: Int? = null
        if (flags and ENCRYPTED != 0) {
            var k = hash(name.substringAfterLast('\\'), 3)
            if (flags and FIX_KEY != 0) k = (k + pos.toInt()) xor size
            key = k
        }
        if (flags and SINGLE_UNIT != 0) return unpack(raw, size, flags)
        if (flags and (IMPLODED or COMPRESSED) == 0) {
            if (key != null) decrypt(raw, key)
            return raw
        }
        val sectors = (size + sectorSize - 1) / sectorSize
        val offsets = IntArray(sectors + 1) { ByteBuffer.wrap(raw, it * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int }
        if (key != null) decrypt(offsets, key - 1)
        val out = ByteArrayOutputStream(size)
        for (s in 0 until sectors) {
            val chunk = raw.copyOfRange(offsets[s], offsets[s + 1])
            if (key != null) decrypt(chunk, key + s)
            out.write(unpack(chunk, minOf(sectorSize, size - s * sectorSize), flags))
        }
        return out.toByteArray()
    }

    private fun unpack(chunk: ByteArray, want: Int, flags: Int): ByteArray {
        if (chunk.size == want) return chunk
        if (flags and IMPLODED != 0) return explode(chunk, 0, want)
        val mask = chunk[0].toInt() and 0xFF
        var data = chunk.copyOfRange(1, chunk.size)
        if (mask and 0x10 != 0) throw UnsupportedOperationException("bzip2")
        if (mask and 0x08 != 0) data = explode(data, 0, want)
        if (mask and 0x02 != 0) data = inflate(data, want)
        if (mask and 0x1A.inv() != 0) throw UnsupportedOperationException("compression $mask")
        return data
    }

    override fun close() = f.close()

    companion object {
        private const val EXISTS = 0x80000000.toInt()
        private const val SINGLE_UNIT = 0x01000000
        private const val FIX_KEY = 0x00020000
        private const val ENCRYPTED = 0x00010000
        private const val COMPRESSED = 0x00000200
        private const val IMPLODED = 0x00000100

        private val CRYPT = IntArray(0x500).also { t ->
            var seed = 0x00100001
            for (i in 0 until 0x100) {
                var j = i
                repeat(5) {
                    seed = (seed * 125 + 3) % 0x2AAAAB
                    val a = (seed and 0xFFFF) shl 16
                    seed = (seed * 125 + 3) % 0x2AAAAB
                    t[j] = a or (seed and 0xFFFF)
                    j += 0x100
                }
            }
        }

        fun hash(s: String, kind: Int): Int {
            var seed1 = 0x7FED7FED
            var seed2 = 0xEEEEEEEE.toInt()
            for (c in s.uppercase().replace('/', '\\')) {
                val ch = c.code and 0xFF
                seed1 = CRYPT[kind * 0x100 + ch] xor (seed1 + seed2)
                seed2 = ch + seed1 + seed2 + (seed2 shl 5) + 3
            }
            return seed1
        }

        private fun decrypt(words: IntArray, key0: Int) {
            var key = key0
            var seed = 0xEEEEEEEE.toInt()
            for (i in words.indices) {
                seed += CRYPT[0x400 + (key and 0xFF)]
                val v = words[i] xor (key + seed)
                key = ((key.inv() shl 0x15) + 0x11111111) or (key ushr 0x0B)
                seed = v + seed + (seed shl 5) + 3
                words[i] = v
            }
        }

        private fun decrypt(bytes: ByteArray, key: Int) {
            val n = bytes.size / 4
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val words = IntArray(n) { b.getInt(it * 4) }
            decrypt(words, key)
            for (i in 0 until n) b.putInt(i * 4, words[i])
        }

        private fun inflate(data: ByteArray, want: Int): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val out = ByteArray(want)
                var n = 0
                while (n < want && !inflater.finished()) {
                    val got = inflater.inflate(out, n, want - n)
                    if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    n += got
                }
                return if (n == want) out else out.copyOf(n)
            } finally {
                inflater.end()
            }
        }

        // PKWARE Data Compression Library "explode" (binary mode, as the archives use).
        private val LEN_BITS = intArrayOf(3, 2, 3, 3, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 7, 7)
        private val LEN_CODE = intArrayOf(5, 3, 1, 6, 10, 2, 12, 20, 4, 24, 8, 48, 16, 32, 64, 0)
        private val EX_LEN_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)
        private val LEN_BASE = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 14, 22, 38, 70, 134, 262)
        private val DIST_BITS = intArrayOf(
            2, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
        )
        private val DIST_CODE = intArrayOf(
            0x03, 0x0D, 0x05, 0x19, 0x09, 0x11, 0x01, 0x3E, 0x1E, 0x2E, 0x0E, 0x36, 0x16, 0x26, 0x06, 0x3A,
            0x1A, 0x2A, 0x0A, 0x32, 0x12, 0x22, 0x42, 0x02, 0x7C, 0x3C, 0x5C, 0x1C, 0x6C, 0x2C, 0x4C, 0x0C,
            0x74, 0x34, 0x54, 0x14, 0x64, 0x24, 0x44, 0x04, 0x78, 0x38, 0x58, 0x18, 0x68, 0x28, 0x48, 0x08,
            0xF0, 0x70, 0xB0, 0x30, 0xD0, 0x50, 0x90, 0x10, 0xE0, 0x60, 0xA0, 0x20, 0xC0, 0x40, 0x80, 0x00,
        )

        /** Symbol by (code read low bit first) for each code length: [length][code] -> symbol + 1. */
        private fun decoder(bits: IntArray, codes: IntArray, max: Int): Array<IntArray> =
            Array(max + 1) { len -> IntArray(1 shl len) }.also { t ->
                for (i in bits.indices) t[bits[i]][codes[i]] = i + 1
            }

        private val LEN_DECODER = decoder(LEN_BITS, LEN_CODE, 7)
        private val DIST_DECODER = decoder(DIST_BITS, DIST_CODE, 8)

        fun explode(data: ByteArray, from: Int, want: Int): ByteArray {
            if (data[from].toInt() != 0) throw UnsupportedOperationException("PKWARE ASCII mode")
            val dictBits = data[from + 1].toInt()
            var pos = from + 2
            var buf = 0L
            var have = 0
            fun bits(n: Int): Int {
                while (have < n) {
                    val b = if (pos < data.size) data[pos].toInt() and 0xFF else 0
                    pos++
                    buf = buf or (b.toLong() shl have)
                    have += 8
                }
                val v = (buf and ((1L shl n) - 1)).toInt()
                buf = buf ushr n
                have -= n
                return v
            }
            fun symbol(table: Array<IntArray>, max: Int): Int {
                var code = 0
                for (len in 1..max) {
                    code = code or (bits(1) shl (len - 1))
                    val s = table[len][code]
                    if (s != 0) return s - 1
                }
                throw IllegalStateException("bad PKWARE code")
            }
            val out = ByteArrayOutputStream(want)
            var buffer = ByteArray(maxOf(want, 16))
            var n = 0
            while (true) {
                if (pos > data.size + 4) break
                if (bits(1) == 1) {
                    val s = symbol(LEN_DECODER, 7)
                    val length = LEN_BASE[s] + bits(EX_LEN_BITS[s]) + 2
                    if (length == 519) break
                    val d = symbol(DIST_DECODER, 8)
                    val dist = (if (length == 2) (d shl 2) or bits(2) else (d shl dictBits) or bits(dictBits)) + 1
                    if (dist > n) throw IllegalStateException("bad PKWARE distance")
                    if (n + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, n + length))
                    for (k in 0 until length) {
                        buffer[n] = buffer[n - dist]
                        n++
                    }
                } else {
                    if (n + 1 > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                    buffer[n++] = bits(8).toByte()
                }
            }
            out.write(buffer, 0, n)
            return out.toByteArray()
        }
    }
}

/** The game's files across its archives, the first that has a file winning, as the game reads them. */
internal class GameArchives(private val mpqs: List<Mpq>) : AutoCloseable {
    fun read(name: String): ByteArray? {
        for (m in mpqs) {
            val d = try { m.read(name) } catch (_: Exception) { null }
            if (d != null) return d
        }
        return null
    }

    override fun close() = mpqs.forEach { it.close() }

    companion object {
        /** PD2's archives first, then the patch, the expansion and the classic game's. */
        private val ORDER = listOf(
            "ProjectD2/pd2data.mpq", "ProjectD2/pd2assets.mpq", "ProjectD2/patch_d2.mpq",
            "patch_d2.mpq", "Patch_D2.mpq", "d2exp.mpq", "d2data.mpq",
        )

        fun open(gameFolder: File): GameArchives? {
            val mpqs = ORDER.mapNotNull { name ->
                val f = File(gameFolder, name)
                if (!f.isFile) null else try { Mpq(f) } catch (_: Exception) { null }
            }
            return if (mpqs.isEmpty()) null else GameArchives(mpqs)
        }
    }
}
