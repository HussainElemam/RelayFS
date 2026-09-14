package io.relayfs.android.core.sync.hash

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * High-throughput file hasher implementing zero-dependency xxHash64 with SHA-256 fallback.
 */
object FastHasher {

    private const val PRIME64_1 = -7046029254386353131L // 0x9E3779B185EBCA87L
    private const val PRIME64_2 = -4417276706814115153L // 0xC2B2AE3D27D4EB4FL
    private const val PRIME64_3 = 1609587929392839161L  // 0x165667B19E3779F9L
    private const val PRIME64_4 = -8796714831421723037L // 0x85EBCA77C2B2AE63L
    private const val PRIME64_5 = 287017772421260170L   // 0x03FB8739509923E2L

    private const val BUFFER_SIZE = 64 * 1024 // 64KB buffer for streaming

    /**
     * Computes the 64-bit xxHash of a file using buffered streaming.
     */
    fun hashFile(file: File, seed: Long = 0L): String {
        FileInputStream(file).use { input ->
            return java.lang.Long.toHexString(hashStream(input, seed))
        }
    }

    /**
     * Computes xxHash64 directly over a byte array.
     */
    fun hashBytes(bytes: ByteArray, seed: Long = 0L): Long {
        val length = bytes.size
        var offset = 0
        var remaining = length

        var h64: Long

        if (length >= 32) {
            var v1 = seed + PRIME64_1 + PRIME64_2
            var v2 = seed + PRIME64_2
            var v3 = seed + 0L
            var v4 = seed - PRIME64_1

            do {
                v1 = round(v1, readLongLE(bytes, offset))
                v2 = round(v2, readLongLE(bytes, offset + 8))
                v3 = round(v3, readLongLE(bytes, offset + 16))
                v4 = round(v4, readLongLE(bytes, offset + 24))

                offset += 32
                remaining -= 32
            } while (remaining >= 32)

            h64 = java.lang.Long.rotateLeft(v1, 1) +
                    java.lang.Long.rotateLeft(v2, 7) +
                    java.lang.Long.rotateLeft(v3, 12) +
                    java.lang.Long.rotateLeft(v4, 18)

            h64 = mergeRound(h64, v1)
            h64 = mergeRound(h64, v2)
            h64 = mergeRound(h64, v3)
            h64 = mergeRound(h64, v4)
        } else {
            h64 = seed + PRIME64_5
        }

        h64 += length.toLong()

        while (remaining >= 8) {
            val k1 = round(0L, readLongLE(bytes, offset))
            h64 = java.lang.Long.rotateLeft(h64 xor k1, 27) * PRIME64_1 + PRIME64_4
            offset += 8
            remaining -= 8
        }

        if (remaining >= 4) {
            val k1 = (readIntLE(bytes, offset).toLong() and 0xFFFFFFFFL) * PRIME64_1
            h64 = java.lang.Long.rotateLeft(h64 xor k1, 23) * PRIME64_2 + PRIME64_3
            offset += 4
            remaining -= 4
        }

        while (remaining > 0) {
            val k1 = (bytes[offset].toLong() and 0xFFL) * PRIME64_5
            h64 = java.lang.Long.rotateLeft(h64 xor k1, 11) * PRIME64_1
            offset++
            remaining--
        }

        // Final avalanche
        h64 = h64 xor (h64 ushr 33)
        h64 *= PRIME64_2
        h64 = h64 xor (h64 ushr 29)
        h64 *= PRIME64_3
        h64 = h64 xor (h64 ushr 32)

        return h64
    }

    /**
     * Computes xxHash64 streaming over an [InputStream].
     */
    fun hashStream(input: InputStream, seed: Long = 0L): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        val chunk32 = ByteArray(32)
        var chunk32Pos = 0
        var totalLength = 0L

        var v1 = seed + PRIME64_1 + PRIME64_2
        var v2 = seed + PRIME64_2
        var v3 = seed + 0L
        var v4 = seed - PRIME64_1
        var processed32 = false

        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            totalLength += bytesRead
            var offset = 0

            // If we have remaining bytes in chunk32 buffer, fill it first
            if (chunk32Pos > 0) {
                val needed = 32 - chunk32Pos
                val toCopy = minOf(needed, bytesRead)
                System.arraycopy(buffer, 0, chunk32, chunk32Pos, toCopy)
                chunk32Pos += toCopy
                offset += toCopy

                if (chunk32Pos == 32) {
                    v1 = round(v1, readLongLE(chunk32, 0))
                    v2 = round(v2, readLongLE(chunk32, 8))
                    v3 = round(v3, readLongLE(chunk32, 16))
                    v4 = round(v4, readLongLE(chunk32, 24))
                    chunk32Pos = 0
                    processed32 = true
                }
            }

            // Process full 32-byte chunks from the buffer
            while (offset + 32 <= bytesRead) {
                v1 = round(v1, readLongLE(buffer, offset))
                v2 = round(v2, readLongLE(buffer, offset + 8))
                v3 = round(v3, readLongLE(buffer, offset + 16))
                v4 = round(v4, readLongLE(buffer, offset + 24))
                offset += 32
                processed32 = true
            }

            // Stash leftover bytes for next read
            val leftovers = bytesRead - offset
            if (leftovers > 0) {
                System.arraycopy(buffer, offset, chunk32, chunk32Pos, leftovers)
                chunk32Pos += leftovers
            }
        }

        var h64: Long
        if (processed32) {
            h64 = java.lang.Long.rotateLeft(v1, 1) +
                    java.lang.Long.rotateLeft(v2, 7) +
                    java.lang.Long.rotateLeft(v3, 12) +
                    java.lang.Long.rotateLeft(v4, 18)

            h64 = mergeRound(h64, v1)
            h64 = mergeRound(h64, v2)
            h64 = mergeRound(h64, v3)
            h64 = mergeRound(h64, v4)
        } else {
            h64 = seed + PRIME64_5
        }

        h64 += totalLength

        // Process remaining bytes in chunk32
        var remOffset = 0
        var remLength = chunk32Pos

        while (remLength >= 8) {
            val k1 = round(0L, readLongLE(chunk32, remOffset))
            h64 = java.lang.Long.rotateLeft(h64 xor k1, 27) * PRIME64_1 + PRIME64_4
            remOffset += 8
            remLength -= 8
        }

        if (remLength >= 4) {
            val k1 = (readIntLE(chunk32, remOffset).toLong() and 0xFFFFFFFFL) * PRIME64_1
            h64 = java.lang.Long.rotateLeft(h64 xor k1, 23) * PRIME64_2 + PRIME64_3
            remOffset += 4
            remLength -= 4
        }

        while (remLength > 0) {
            val k1 = (chunk32[remOffset].toLong() and 0xFFL) * PRIME64_5
            h64 = java.lang.Long.rotateLeft(h64 xor k1, 11) * PRIME64_1
            remOffset++
            remLength--
        }

        // Final avalanche
        h64 = h64 xor (h64 ushr 33)
        h64 *= PRIME64_2
        h64 = h64 xor (h64 ushr 29)
        h64 *= PRIME64_3
        h64 = h64 xor (h64 ushr 32)

        return h64
    }

    /**
     * Fallback cryptographic SHA-256 hex string computation.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun round(acc: Long, input: Long): Long {
        var v = acc + (input * PRIME64_2)
        v = java.lang.Long.rotateLeft(v, 31)
        v *= PRIME64_1
        return v
    }

    private fun mergeRound(acc: Long, v: Long): Long {
        val k1 = round(0L, v)
        var h = acc xor k1
        h = h * PRIME64_1 + PRIME64_4
        return h
    }

    private fun readLongLE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24) or
                ((bytes[offset + 4].toLong() and 0xFFL) shl 32) or
                ((bytes[offset + 5].toLong() and 0xFFL) shl 40) or
                ((bytes[offset + 6].toLong() and 0xFFL) shl 48) or
                ((bytes[offset + 7].toLong() and 0xFFL) shl 56)
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
