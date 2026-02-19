package com.karaoke.app.audio

import kotlin.math.*

/**
 * Frame-based YIN pitch (F0) estimator.
 * Reference: de Cheveigné & Kawahara, 2002 - "YIN, a fundamental frequency estimator"
 *
 * Uses 10th/90th percentile across all voiced frames as lowest/highest note.
 */
object PitchDetector {

    private const val FRAME_SIZE = 2048      // ~46ms at 44100Hz
    private const val HOP_SIZE = 512
    private const val YIN_THRESHOLD = 0.15f
    private const val MIN_F0_HZ = 80f
    private const val MAX_F0_HZ = 1000f
    private const val SILENCE_THRESHOLD = 500.0

    data class PitchResult(
        val lowestHz: Float,
        val highestHz: Float,
        val lowestNote: String,
        val highestNote: String
    )

    fun analyse(samples: ShortArray, sampleRate: Int): PitchResult? {
        val pitches = mutableListOf<Float>()

        var pos = 0
        val frame = FloatArray(FRAME_SIZE)

        while (pos + FRAME_SIZE <= samples.size) {
            for (i in 0 until FRAME_SIZE) frame[i] = samples[pos + i].toFloat()

            val rms = sqrt(frame.map { it * it }.average())
            if (rms < SILENCE_THRESHOLD) { pos += HOP_SIZE; continue }

            val f0 = yinF0(frame, sampleRate)
            if (f0 != null && f0 in MIN_F0_HZ..MAX_F0_HZ) pitches.add(f0)
            pos += HOP_SIZE
        }

        if (pitches.size < 5) return null

        pitches.sort()
        val lowestHz  = pitches[(pitches.size * 0.10).toInt().coerceAtLeast(0)]
        val highestHz = pitches[(pitches.size * 0.90).toInt().coerceAtMost(pitches.size - 1)]

        return PitchResult(lowestHz, highestHz, hzToNoteName(lowestHz), hzToNoteName(highestHz))
    }

    private fun yinF0(frame: FloatArray, sampleRate: Int): Float? {
        val n = frame.size
        val halfN = n / 2
        val d = FloatArray(halfN)

        // Difference function
        for (tau in 1 until halfN) {
            var sum = 0.0
            for (j in 0 until halfN) {
                val diff = frame[j] - frame[j + tau]
                sum += diff * diff
            }
            d[tau] = sum.toFloat()
        }

        // Cumulative mean normalised difference
        val cmndf = FloatArray(halfN)
        cmndf[0] = 1f
        var runningSum = 0f
        for (tau in 1 until halfN) {
            runningSum += d[tau]
            cmndf[tau] = if (runningSum == 0f) 1f else d[tau] * tau / runningSum
        }

        val minTau = (sampleRate / MAX_F0_HZ).toInt()
        val maxTau = (sampleRate / MIN_F0_HZ).toInt().coerceAtMost(halfN - 1)

        // First dip below threshold (with parabolic interpolation)
        var tau = minTau
        while (tau < maxTau) {
            if (cmndf[tau] < YIN_THRESHOLD) {
                val betterTau = if (tau > 0 && tau < maxTau) {
                    val s0 = cmndf[tau - 1]; val s1 = cmndf[tau]; val s2 = cmndf[tau + 1]
                    val denom = s0 - 2 * s1 + s2
                    if (denom == 0f) tau.toFloat() else tau + (s0 - s2) / (2 * denom)
                } else tau.toFloat()
                return if (betterTau > 0) sampleRate / betterTau else null
            }
            tau++
        }

        // Global minimum fallback
        var minVal = Float.MAX_VALUE; var minIdx = minTau
        for (t in minTau..maxTau) {
            if (cmndf[t] < minVal) { minVal = cmndf[t]; minIdx = t }
        }
        return if (minVal < 0.5f && minIdx > 0) sampleRate.toFloat() / minIdx else null
    }

    // A4 = 440 Hz = MIDI 69
    fun hzToNoteName(hz: Float): String {
        if (hz <= 0) return "?"
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val midi = 12 * log2(hz / 440.0) + 69
        val rounded = midi.roundToInt()
        val octave = rounded / 12 - 1
        val noteIdx = ((rounded % 12) + 12) % 12
        return "${noteNames[noteIdx]}$octave (${hz.toInt()} Hz)"
    }

    fun hzToMidi(hz: Float): Float {
        if (hz <= 0) return 0f
        return (12 * log2(hz / 440.0) + 69).toFloat()
    }

    fun noteNameToHz(note: String): Float {
        val noteMap = mapOf(
            "C" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3,
            "E" to 4, "F" to 5, "F#" to 6, "Gb" to 6, "G" to 7, "G#" to 8,
            "Ab" to 8, "A" to 9, "A#" to 10, "Bb" to 10, "B" to 11
        )
        val match = Regex("([A-G]#?b?)(-?\\d+)").find(note) ?: return 0f
        val noteNum = noteMap[match.groupValues[1]] ?: return 0f
        val midi = (match.groupValues[2].toInt() + 1) * 12 + noteNum
        return 440f * 2f.pow((midi - 69).toFloat() / 12f)
    }
}
