package com.oneononearena.videoclip.feasibility

import java.io.File
import java.io.RandomAccessFile

internal class Mp4BoxScanner(private val file: File) {
    fun contains(type: String): Boolean {
        require(type.length == 4) { "MP4 box types are four characters" }
        RandomAccessFile(file, "r").use { input -> return containsIn(input, 0L, input.length(), type) }
    }

    fun editListMediaTimes(): List<Long> = RandomAccessFile(file, "r").use { input ->
        editListMediaTimesIn(input, 0L, input.length())
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

    private fun editListMediaTimesIn(input: RandomAccessFile, start: Long, end: Long): List<Long> {
        val mediaTimes = mutableListOf<Long>()
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
                    require(offset + 16 <= end) { "Truncated 64-bit MP4 box" }
                    headerSize = 16L
                    val size64 = input.readLong()
                    require(size64 >= headerSize && size64 <= end - offset) { "Invalid 64-bit MP4 box size" }
                    boxEnd = offset + size64
                }
                else -> { headerSize = 8L; boxEnd = offset + size32 }
            }
            require(boxEnd > offset && boxEnd <= end && boxEnd - offset >= headerSize) { "Invalid MP4 box size" }
            when {
                type == "elst" -> mediaTimes += readEditListMediaTimes(input, offset + headerSize, boxEnd)
                type in CONTAINERS -> mediaTimes += editListMediaTimesIn(input, offset + headerSize, boxEnd)
            }
            offset = boxEnd
        }
        return mediaTimes
    }

    private fun readEditListMediaTimes(input: RandomAccessFile, payloadStart: Long, boxEnd: Long): List<Long> {
        require(boxEnd - payloadStart >= 8L) { "Truncated MP4 edit list" }
        input.seek(payloadStart)
        val version = input.readUnsignedByte()
        input.seek(input.filePointer + 3L)
        val entryCount = input.readInt().toLong() and 0xffff_ffffL
        val entrySize = when (version) {
            0 -> 12L
            1 -> 20L
            else -> throw IllegalArgumentException("Unsupported MP4 edit-list version: $version")
        }
        require(entryCount <= (boxEnd - input.filePointer) / entrySize) { "Truncated MP4 edit-list entry" }
        val mediaTimes = ArrayList<Long>(entryCount.toInt())
        repeat(entryCount.toInt()) {
            input.seek(input.filePointer + if (version == 0) 4L else 8L)
            mediaTimes += if (version == 0) input.readInt().toLong() else input.readLong()
            input.seek(input.filePointer + 4L)
        }
        return mediaTimes
    }

    private companion object { val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts") }
}
