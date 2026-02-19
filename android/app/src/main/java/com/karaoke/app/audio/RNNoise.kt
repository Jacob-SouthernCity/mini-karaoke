package com.karaoke.app.audio

import java.io.File
import kotlin.math.floor

/**
 * On-device vocal denoising.
 *
 * Preferred path: native RNNoise neural model (JNI).
 * Fallback path: spectral Wiener denoiser for environments where JNI/NDK is unavailable.
 */
object RNNoise {
    private const val RNNOISE_RATE = 48_000
    private const val FRAME_SIZE = 480

    fun denoise(inputFile: File, sampleRate: Int): ShortArray {
        if (!NativeRnnoise.isAvailable) return SpectralDenoiser.denoise(inputFile)

        return try {
            denoiseWithNativeModel(inputFile, sampleRate)
        } catch (_: Throwable) {
            SpectralDenoiser.denoise(inputFile)
        }
    }

    private fun denoiseWithNativeModel(inputFile: File, sampleRate: Int): ShortArray {
        val pcm16 = WavUtils.readPcmSamples(inputFile)
        if (pcm16.isEmpty()) return pcm16

        val input48k = if (sampleRate == RNNOISE_RATE) {
            shortToFloat(pcm16)
        } else {
            resample(shortToFloat(pcm16), sampleRate, RNNOISE_RATE)
        }

        val denoised48k = FloatArray(input48k.size)
        val padded = if (input48k.size % FRAME_SIZE == 0) input48k.size else {
            input48k.size + (FRAME_SIZE - input48k.size % FRAME_SIZE)
        }

        val inputPadded = FloatArray(padded)
        input48k.copyInto(inputPadded)

        val handle = NativeRnnoise.createState()
        if (handle == 0L) throw IllegalStateException("RNNoise state create failed")

        try {
            val inFrame = FloatArray(FRAME_SIZE)
            val outFrame = FloatArray(FRAME_SIZE)
            var off = 0
            while (off < padded) {
                inputPadded.copyInto(inFrame, 0, off, off + FRAME_SIZE)
                NativeRnnoise.processFrame(handle, inFrame, outFrame)
                val copyLen = minOf(FRAME_SIZE, denoised48k.size - off)
                if (copyLen > 0) outFrame.copyInto(denoised48k, off, 0, copyLen)
                off += FRAME_SIZE
            }
        } finally {
            NativeRnnoise.destroyState(handle)
        }

        val output = if (sampleRate == RNNOISE_RATE) {
            denoised48k
        } else {
            resample(denoised48k, RNNOISE_RATE, sampleRate)
        }

        return floatToShort(output, pcm16.size)
    }

    private fun shortToFloat(input: ShortArray): FloatArray {
        return FloatArray(input.size) { i -> input[i] / 32768f }
    }

    private fun floatToShort(input: FloatArray, outLen: Int): ShortArray {
        val len = minOf(input.size, outLen)
        return ShortArray(len) { i ->
            val s = (input[i] * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            s.toShort()
        }
    }

    // Linear interpolation resampler, sufficient for denoiser pre/post processing.
    private fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (input.isEmpty() || inRate == outRate) return input.copyOf()
        val outSize = ((input.size.toLong() * outRate) / inRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outSize)
        val ratio = inRate.toDouble() / outRate.toDouble()

        for (i in 0 until outSize) {
            val pos = i * ratio
            val idx = floor(pos).toInt()
            val frac = (pos - idx).toFloat()
            val a = input[idx.coerceIn(0, input.lastIndex)]
            val b = input[(idx + 1).coerceIn(0, input.lastIndex)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
