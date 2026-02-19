package com.karaoke.app.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * FFT Wiener spectral subtraction fallback when RNNoise native model is unavailable.
 */
object SpectralDenoiser {
    private const val FFT_SIZE = 1024
    private const val HOP = FFT_SIZE / 2
    private const val ALPHA = 2.0f
    private const val FLOOR = 0.07f

    fun denoise(inputFile: File): ShortArray {
        val samples = WavUtils.readPcmSamples(inputFile)
        val n = FFT_SIZE
        if (samples.size < n * 4) return samples

        val hann = FloatArray(n) { i -> (0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat() }
        val numFrames = (samples.size - n) / HOP + 1

        val frameRms = FloatArray(numFrames) { fi ->
            val start = fi * HOP
            var sum = 0.0
            for (j in 0 until n) {
                val s = if (start + j < samples.size) samples[start + j].toFloat() else 0f
                sum += s * s
            }
            sqrt(sum / n).toFloat()
        }

        val sortedRms = frameRms.copyOf().also { it.sort() }
        val noiseThreshold = sortedRms[max(0, numFrames / 5)]

        val noisePsd = FloatArray(n)
        var noiseCount = 0
        for (fi in 0 until numFrames) {
            if (frameRms[fi] <= noiseThreshold) {
                val start = fi * HOP
                val re = FloatArray(n) { j ->
                    if (start + j < samples.size) samples[start + j].toFloat() * hann[j] else 0f
                }
                val im = FloatArray(n)
                fft(re, im)
                for (k in 0 until n) noisePsd[k] += re[k] * re[k] + im[k] * im[k]
                noiseCount++
            }
        }
        if (noiseCount > 0) {
            val inv = 1f / noiseCount
            for (k in noisePsd.indices) noisePsd[k] *= inv
        }

        val output = FloatArray(samples.size + n)
        val windowSum = FloatArray(samples.size + n)

        for (fi in 0 until numFrames) {
            val start = fi * HOP
            val re = FloatArray(n) { j ->
                if (start + j < samples.size) samples[start + j].toFloat() * hann[j] else 0f
            }
            val im = FloatArray(n)
            fft(re, im)

            for (k in 0 until n) {
                val psd = re[k] * re[k] + im[k] * im[k]
                val gain = if (psd > 1e-10f) {
                    kotlin.math.max(FLOOR, 1f - ALPHA * noisePsd[k] / psd)
                } else FLOOR
                re[k] *= gain
                im[k] *= gain
            }

            fft(re, im, inverse = true)

            for (j in 0 until n) {
                if (start + j < output.size) {
                    output[start + j] += re[j] * hann[j]
                    windowSum[start + j] += hann[j] * hann[j]
                }
            }
        }

        return ShortArray(samples.size) { i ->
            val v = if (windowSum[i] > 1e-6f) output[i] / windowSum[i] else output[i]
            v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        val n = re.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]
                re[i] = re[j]
                re[j] = tmp
                tmp = im[i]
                im[i] = im[j]
                im[j] = tmp
            }
        }

        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * PI / len
            val wBaseRe = cos(ang).toFloat()
            val wBaseIm = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                for (jj in 0 until len / 2) {
                    val uRe = re[i + jj]
                    val uIm = im[i + jj]
                    val tRe = wRe * re[i + jj + len / 2] - wIm * im[i + jj + len / 2]
                    val tIm = wRe * im[i + jj + len / 2] + wIm * re[i + jj + len / 2]
                    re[i + jj] = uRe + tRe
                    im[i + jj] = uIm + tIm
                    re[i + jj + len / 2] = uRe - tRe
                    im[i + jj + len / 2] = uIm - tIm
                    val newWRe = wRe * wBaseRe - wIm * wBaseIm
                    wIm = wRe * wBaseIm + wIm * wBaseRe
                    wRe = newWRe
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1f / n
            for (i in re.indices) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }
}
