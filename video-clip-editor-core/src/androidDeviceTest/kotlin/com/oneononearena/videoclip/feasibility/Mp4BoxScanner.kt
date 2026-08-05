package com.oneononearena.videoclip.feasibility

import java.io.File
import java.io.RandomAccessFile

internal class Mp4BoxScanner(private val file: File) {
    fun contains(type: String): Boolean {
        require(type.length == 4) { "MP4 box types are four characters" }
        RandomAccessFile(file, "r").use { input -> return containsIn(input, 0L, input.length(), type) }
    }

    private fun containsIn(input: RandomAccessFile, start: Long, end: Long, wanted: String): Boolean {
        var offset = start
        while (offset + 8 <= end) {
            input.seek(offset)
            val size32 = input.readInt().toLong() and 0xffff_ffffL
            val type = ByteArray(4).also(input::readFully).decodeToString()
            val headerSize: Long
            val boxEnd: Long
            when (size32) {
                0L -> { headerSize = 8L; boxEnd = end }
                1L -> {
                    if (offset + 16 > end) return false
                    headerSize = 16L
                    val size64 = input.readLong()
                    if (size64 < headerSize || size64 > end - offset) return false
                    boxEnd = offset + size64
                }
                else -> { headerSize = 8L; boxEnd = offset + size32 }
            }
            if (boxEnd <= offset || boxEnd > end || boxEnd - offset < headerSize) return false
            if (type == wanted) return true
            if (type in CONTAINERS && containsIn(input, offset + headerSize, boxEnd, wanted)) return true
            offset = boxEnd
        }
        return false
    }

    private companion object { val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts") }
}
