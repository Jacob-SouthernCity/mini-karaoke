package com.karaoke.app.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utilities for reading/writing WAV files with PCM16 mono audio.
 */
object WavUtils {

    /**
     * Write a 44-byte WAV header to a FileOutputStream.
     * Call this before writing PCM data. Use [fixWavHeader] to update
     * the data size after all PCM bytes have been written.
     */
    fun writeWavHeader(out: FileOutputStream, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF chunk
            put("RIFF".toByteArray())
            putInt(0)             // placeholder: total file size - 8
            put("WAVE".toByteArray())
            // fmt sub-chunk
            put("fmt ".toByteArray())
            putInt(16)            // PCM sub-chunk size
            putShort(1)           // AudioFormat = PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            // data sub-chunk
            put("data".toByteArray())
            putInt(0)             // placeholder: data size
        }
        out.write(header.array())
    }

    /**
     * After all PCM data is written, seek back to fix the size fields in the header.
     */
    fun fixWavHeader(file: File) {
        val size = file.length()
        val raf = RandomAccessFile(file, "rw")
        raf.use {
            // RIFF chunk size = total - 8
            it.seek(4)
            it.write(intToLittleEndian((size - 8).toInt()))
            // data chunk size = total - 44
            it.seek(40)
            it.write(intToLittleEndian((size - 44).toInt()))
        }
    }

    /**
     * Read all PCM samples (Int16) from a WAV file, skipping the 44-byte header.
     */
    fun readPcmSamples(file: File): ShortArray {
        val bytes = file.readBytes()
        val dataOffset = 44
        val numSamples = (bytes.size - dataOffset) / 2
        val samples = ShortArray(numSamples)
        val buf = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            samples[i] = buf.short
        }
        return samples
    }

    /**
     * Write PCM Int16 samples to a WAV file.
     */
    fun writePcmToWav(file: File, samples: ShortArray, sampleRate: Int, channels: Int = 1) {
        FileOutputStream(file).use { out ->
            writeWavHeader(out, sampleRate, channels)
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buf.putShort(s)
            out.write(buf.array())
        }
        fixWavHeader(file)
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    /**
     * Mix two same-length short arrays: result[i] = alpha * a[i] + (1-alpha) * b[i]
     * Clamps to Short range.
     */
    fun mixSamples(a: ShortArray, b: ShortArray, alpha: Float): ShortArray {
        val len = minOf(a.size, b.size)
        return ShortArray(len) { i ->
            val mixed = alpha * a[i] + (1f - alpha) * b[i]
            mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Compute sample-wise: noise[i] = original[i] - clean[i], clamped.
     */
    fun subtractSamples(original: ShortArray, clean: ShortArray): ShortArray {
        val len = minOf(original.size, clean.size)
        return ShortArray(len) { i ->
            val diff = original[i].toInt() - clean[i].toInt()
            diff.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
