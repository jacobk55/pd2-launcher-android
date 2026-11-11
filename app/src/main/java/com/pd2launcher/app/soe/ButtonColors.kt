package com.pd2launcher.app.soe

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The controller button pictures of Project Diablo 2 (the A, B, Y, X faces on loot labels and
 * menus, in `pd2assets.mpq`) recoloured to the AYN Thor's buttons: A red, B yellow, Y green, X blue (the palette's true
 * colors: the art is Xbox's A green, B red, Y orange, X blue, so X is left as it is).
 *
 * The two sprite files are replaced inside the archive in place: the new data is appended to the
 * archive, the block table entry of each file points at it (stored uncompressed), and the archive
 * size in the header grows. Nothing else in the archive moves, so the game opens it as before.
 */
internal object ButtonColors {
    private const val DIR = "data\\global\\ui\\CONTROLS\\"

    private class Sprite(val name: String, val size: Int, val base64: String)

    private val sprites = listOf(
        Sprite(DIR + "Controls_Xbox_10.dc6", 2216, XBOX_10),
        Sprite(DIR + "Controls_Xbox_20.dc6", 4721, XBOX_20),
    )

    /**
     * Patches [archive] (a copy that may be changed): true when the recoloured sprites are in,
     * false when the archive does not have the expected files (nothing changed then).
     */
    fun apply(archive: File): Boolean {
        RandomAccessFile(archive, "rw").use { file ->
            val head = ByteArray(32)
            file.seek(0)
            file.readFully(head)
            val hd = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (hd.getInt(0) != 0x1A51504D || hd.getInt(4) != 32 || hd.getShort(12).toInt() != 0) return false
            val sectorShift = hd.getShort(14).toInt()
            if (sectorShift < 0) return false
            val hashPos = hd.getInt(16).toLong() and 0xFFFFFFFFL
            val blockPos = hd.getInt(20).toLong() and 0xFFFFFFFFL
            val hashCount = hd.getInt(24)
            val blockCount = hd.getInt(28)
            if (hashCount <= 0 || hashCount and (hashCount - 1) != 0 || blockCount <= 0 || hashCount > 0x400000 || blockCount > 0x400000) return false
            val hashes = readTable(file, hashPos, hashCount, "(hash table)")
            val blocks = readTable(file, blockPos, blockCount, "(block table)")
            var end = file.length()
            var patched = 0
            for (sprite in sprites) {
                val data = Base64.getDecoder().decode(sprite.base64.replace(" ", ""))
                if (data.size != sprite.size) continue
                val block = blockOf(hashes, hashCount, sprite.name)
                if (block < 0 || block >= blockCount) continue
                // Only the file of the expected size: the same picture set the colours were made for.
                if (blocks.getInt(block * 16 + 8) != sprite.size) continue
                file.seek(end)
                file.write(data)
                blocks.putInt(block * 16, end.toInt())
                blocks.putInt(block * 16 + 4, data.size)
                blocks.putInt(block * 16 + 8, data.size)
                blocks.putInt(block * 16 + 12, 0x80000000.toInt())
                end += data.size
                patched++
            }
            if (patched == 0) return false
            if (end > 0xFFFFFFFFL) return false
            writeTable(file, blockPos, blocks, "(block table)")
            file.seek(8)
            file.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(end.toInt()).array())
            return true
        }
    }

    private fun readTable(file: RandomAccessFile, position: Long, count: Int, key: String): ByteBuffer {
        val raw = ByteArray(count * 16)
        file.seek(position)
        file.readFully(raw)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        crypt(buffer, count * 4, hash(key, 3), decrypt = true)
        return buffer
    }

    private fun writeTable(file: RandomAccessFile, position: Long, table: ByteBuffer, key: String) {
        val copy = ByteBuffer.wrap(table.array().copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        crypt(copy, copy.capacity() / 4, hash(key, 3), decrypt = false)
        file.seek(position)
        file.write(copy.array())
    }

    /** The block index of [name] in the hash table, or -1. */
    private fun blockOf(hashes: ByteBuffer, count: Int, name: String): Int {
        val a = hash(name, 1)
        val b = hash(name, 2)
        val start = hash(name, 0) and (count - 1)
        var i = start
        do {
            val at = i * 16
            val block = hashes.getInt(at + 12)
            if (block == -1 && hashes.getInt(at) == -1) return -1
            if (hashes.getInt(at) == a && hashes.getInt(at + 4) == b && block != -1 && block != -2) return block
            i = (i + 1) and (count - 1)
        } while (i != start)
        return -1
    }

    private val table: IntArray by lazy {
        val t = IntArray(0x500)
        var seed = 0x00100001L
        for (i in 0 until 256) {
            var index = i
            repeat(5) {
                seed = (seed * 125 + 3) % 0x2AAAABL
                val high = (seed and 0xFFFF) shl 16
                seed = (seed * 125 + 3) % 0x2AAAABL
                val low = seed and 0xFFFF
                t[index] = (high or low).toInt()
                index += 0x100
            }
        }
        t
    }

    private fun hash(text: String, type: Int): Int {
        var s1 = 0x7FED7FED
        var s2 = 0xEEEEEEEE.toInt()
        for (ch in text.uppercase()) {
            val c = ch.code and 0xFF
            s1 = table[type * 0x100 + c] xor (s1 + s2)
            s2 = c + s1 + s2 + (s2 shl 5) + 3
        }
        return s1
    }

    private fun crypt(buffer: ByteBuffer, words: Int, start: Int, decrypt: Boolean) {
        var key = start
        var s2 = 0xEEEEEEEE.toInt()
        for (i in 0 until words) {
            s2 += table[0x400 + (key and 0xFF)]
            val v = buffer.getInt(i * 4)
            val out = v xor (key + s2)
            key = ((key.inv() shl 0x15) + 0x11111111) or (key ushr 0x0B)
            s2 = (if (decrypt) out else v) + s2 + (s2 shl 5) + 3
            buffer.putInt(i * 4, out)
        }
    }

    private const val XBOX_10 =
        "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" +
        ""

    private const val XBOX_20 =
        "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" +
        "D1OmplM8rbQhrUSnp8xqIYCCEVq1tbW3ERC8tQ9AQDwJu6etgIIRpre1vretGRW0Z91qp6dqBGqAghGmyhnDvhG3w7NnIKcMZAsn" +
        "aoCCEVO3Fca1ISETtQ9ARTxOKmQhgIMPU1NT3Q0hrq+tDqenz6chgIcG3b7GvhNTgIcGWsa8tRMOgIcGphPAFbmmgIcGU7UVt7fa" +
        "gIgEU6amWoCAgM3NzQAAAAAUAAAAFAAAAAAAAAAAAAAAAAAAAAQIAADMAAAAgICIBFOmplqAhwZTE768tVOAhwaltxbAE1OAhwal" +
        "tbUVx1qAhwZTFha1taaAgw5TpqZTPK20rSE8plNTU4CCEFq1tbW3ERC+FrC1tbUTx1OAghCmt7W+t60ZvsC3D7zGvMPagIIQpsoZ" +
        "w76wsLGzs60TE8e+poCCEFO3Fca1rCFqaq2tt7UZEw6Agw5TU1Pdp0Ug3kQ8U6WmpYCHBqpFp2pAp4CHBqc8DKc8p4CHBsxTZKcJ" +
        "yYCHBqcQC6cUaoCIBGQkA6eAiQKnp4CAzc3NAAAAABQAAAAUAAAAAAAAAAAAAAAAAAAA8wgAAMwAAACAiQKnp4CIBKcDJGSAhwZq" +
        "FKcLEKeAhwbJCadkU8mAhwanPKcMPKeAhwanQGqnRaqAgw5TpqZTPETeIEWlplNTU4CCEFq1tbW3rq1qag8RtbUTx1OAghCmt7W+" +
        "t6wTsbGxrbzGvMPagIIQpsoZw74RE8AZvK4TE8e+poCCEFO3Fca1ISETGa+wt7UZEw6Agw5TU1PdDSGusK08U6WmpYCHBt2+xr4T" +
        "U4CHBlrGvLUTDoCHBqYTwBW5poCHBlO1Fbe32oCIBFOmplqAgIDNzc0AAAAAFAAAABQAAAAAAAAAAAAAAAAAAADrCQAA1QAAAICA" +
        "gICAgg8ODg4ODg4ODg5qampqamqAgREO09TX1Nrd3abXpqam19MOaoCBEWrX1x4e3BKtrdSwsBG/1NSngIEGatfa29vggQvU1Nqu" +
        "19usGddqpYCBBmrXHtzc3YEL29ofrtfdERzXpaeAgQZq19fc3N2BBNzc3q6BBq7b3NfXp4CCBWoO2trdgQsf3N6u3N6s0NfXp4CD" +
        "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" +
        ""
}
