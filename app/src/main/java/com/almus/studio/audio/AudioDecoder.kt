package com.almus.studio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

/**
 * Almus Studio's native engine only decodes WAV (see wav_file.h). Most audio
 * a person picks from their phone is MP3/M4A/OGG, not WAV -- without this,
 * import silently does nothing useful, because the native loader rejects the
 * file and [com.almus.studio.viewmodel.StudioViewModel.importAudioFile]
 * has nothing to show. This uses Android's built-in MediaCodec (whatever
 * formats the device itself supports decoding) to produce a real PCM16 WAV
 * file, entirely on-device -- no network, no bundled codec licensing concern.
 */
object AudioDecoder {

    /** Returns true if [file] starts with a RIFF/WAVE header (cheap check, no full parse). */
    fun looksLikeWav(file: File): Boolean {
        if (file.length() < 12) return false
        return RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE"
        }
    }

    /**
     * Decodes the audio track of [sourceUri] to 16-bit PCM WAV at [outputFile].
     * Returns true on success. Never throws -- failures (unsupported codec,
     * DRM-protected source, corrupt file) result in false so the caller can
     * show a clear message instead of crashing.
     */
    fun decodeToWav(context: Context, sourceUri: Uri, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return false

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmChunks = ArrayList<ByteArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* nothing to do, loop again */ }
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmChunks.add(chunk)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (pcmChunks.isEmpty()) return false
            writePcm16Wav(outputFile, pcmChunks, sampleRate, channelCount)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writePcm16Wav(file: File, chunks: List<ByteArray>, sampleRate: Int, channels: Int) {
        val dataSize = chunks.sumOf { it.size }
        file.outputStream().use { out ->
            fun le32(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()))
            fun le16(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))

            out.write("RIFF".toByteArray())
            le32(36 + dataSize)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            le32(16)
            le16(1) // PCM
            le16(channels)
            le32(sampleRate)
            le32(sampleRate * channels * 2)
            le16(channels * 2)
            le16(16)
            out.write("data".toByteArray())
            le32(dataSize)
            chunks.forEach { out.write(it) }
        }
    }
}
