package com.almus.studio.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Reads a WAV file's PCM data and reduces it to a fixed number of min/max peak
 * pairs for waveform drawing. This is intentionally independent of the native
 * engine (which decodes for playback, not for UI) so the UI thread can render
 * a waveform without touching the audio engine's real-time state at all.
 */
object WaveformAnalyzer {

    data class Peaks(val min: FloatArray, val max: FloatArray)

    private data class HeaderInfo(
        val channels: Int,
        val bitsPerSample: Int,
        val isFloat: Boolean,
        val dataStart: Long,
        val dataSize: Long
    ) {
        val frameSize: Int get() = (bitsPerSample / 8) * channels
        val frameCount: Long get() = if (frameSize > 0) dataSize / frameSize else 0L
    }

    /** Frame count only — cheap, reads just the header chunks, not the sample data.
     * Used right after importing a file so the stored [com.almus.studio.data.AudioClip]
     * has a real length instead of a placeholder. */
    fun frameCount(file: File): Long? {
        val info = readHeader(file) ?: return null
        return info.frameCount.takeIf { it > 0 }
    }

    fun analyze(file: File, bucketCount: Int): Peaks? {
        val info = readHeader(file) ?: return null
        if (info.frameCount <= 0) return null

        RandomAccessFile(file, "r").use { raf ->
            val frameSize = info.frameSize
            val frameCount = info.frameCount.toInt()
            val framesPerBucket = maxOf(1, frameCount / bucketCount)
            val mins = FloatArray(bucketCount)
            val maxs = FloatArray(bucketCount)

            raf.seek(info.dataStart)
            val readBuf = ByteArray(frameSize * min(framesPerBucket, 4096))

            for (bucket in 0 until bucketCount) {
                var bucketMin = 0f
                var bucketMax = 0f
                var framesRemaining = framesPerBucket
                while (framesRemaining > 0) {
                    val framesThisRead = min(framesRemaining, 4096)
                    val bytesToRead = framesThisRead * frameSize
                    if (raf.filePointer + bytesToRead > info.dataStart + info.dataSize) break
                    raf.readFully(readBuf, 0, bytesToRead)
                    for (f in 0 until framesThisRead) {
                        val offset = f * frameSize
                        val sample = sampleToFloat(readBuf, offset, info.bitsPerSample, info.isFloat)
                        if (sample < bucketMin) bucketMin = sample
                        if (sample > bucketMax) bucketMax = sample
                    }
                    framesRemaining -= framesThisRead
                }
                mins[bucket] = bucketMin
                maxs[bucket] = bucketMax
            }
            return Peaks(mins, maxs)
        }
    }

    private fun readHeader(file: File): HeaderInfo? {
        if (!file.exists()) return null
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return null
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null

            var channels = 1
            var bitsPerSample = 16
            var isFloat = false
            var dataStart = -1L
            var dataSize = 0L

            while (raf.filePointer < raf.length() - 8) {
                val chunkId = ByteArray(4)
                raf.readFully(chunkId)
                val sizeBytes = ByteArray(4)
                raf.readFully(sizeBytes)
                val size = leToInt(sizeBytes).toLong() and 0xFFFFFFFFL
                val id = String(chunkId)
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt())
                        raf.readFully(fmt)
                        val formatTag = leToShort(fmt, 0)
                        channels = leToShort(fmt, 2)
                        bitsPerSample = leToShort(fmt, 14)
                        isFloat = formatTag == 3
                    }
                    "data" -> {
                        dataStart = raf.filePointer
                        dataSize = size
                        raf.seek(raf.filePointer + size)
                    }
                    else -> raf.seek(raf.filePointer + size)
                }
                if (size % 2L == 1L) raf.seek(raf.filePointer + 1)
            }

            if (dataStart < 0 || channels <= 0) return null
            return HeaderInfo(channels, bitsPerSample, isFloat, dataStart, dataSize)
        }
    }

    private fun sampleToFloat(buf: ByteArray, offset: Int, bitsPerSample: Int, isFloat: Boolean): Float {
        return when {
            isFloat && bitsPerSample == 32 -> Float.fromBits(leToInt(buf, offset))
            bitsPerSample == 16 -> leToShortSigned(buf, offset) / 32768f
            bitsPerSample == 24 -> {
                val v = (buf[offset].toInt() and 0xFF) or
                        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                        ((buf[offset + 2].toInt() and 0xFF) shl 16)
                val signExtended = if (v and 0x800000 != 0) v or -0x1000000 else v
                signExtended / 8388608f
            }
            bitsPerSample == 32 -> leToInt(buf, offset) / 2147483648f
            else -> 0f
        }
    }

    private fun leToShort(b: ByteArray, offset: Int = 0): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun leToShortSigned(b: ByteArray, offset: Int = 0): Int {
        val unsigned = leToShort(b, offset)
        return if (unsigned and 0x8000 != 0) unsigned or -0x10000 else unsigned
    }

    private fun leToInt(b: ByteArray, offset: Int = 0): Int =
        (b[offset].toInt() and 0xFF) or
        ((b[offset + 1].toInt() and 0xFF) shl 8) or
        ((b[offset + 2].toInt() and 0xFF) shl 16) or
        ((b[offset + 3].toInt() and 0xFF) shl 24)
}
