package com.karaoke.app.audio

/**
 * Classifies voice type (Bass/Tenor/Alto/Soprano) from the singer's pitch range.
 *
 * Score = overlap_ratio(IoU) − 0.05 × semitone_center_distance
 * Canonical ranges: Bass E2-E4, Tenor C3-C5, Alto F3-F5, Soprano C4-C6.
 * Input is the 10th/90th percentile pitch range from PitchDetector.
 */
object VoiceClassifier {

    data class VoiceRange(val name: String, val lowHz: Float, val highHz: Float)

    private val VOICE_RANGES = listOf(
        VoiceRange("Bass",    PitchDetector.noteNameToHz("E2"), PitchDetector.noteNameToHz("E4")),
        VoiceRange("Tenor",   PitchDetector.noteNameToHz("C3"), PitchDetector.noteNameToHz("C5")),
        VoiceRange("Alto",    PitchDetector.noteNameToHz("F3"), PitchDetector.noteNameToHz("F5")),
        VoiceRange("Soprano", PitchDetector.noteNameToHz("C4"), PitchDetector.noteNameToHz("C6"))
    )

    data class ClassificationResult(val voiceType: String, val explanation: String, val score: Float)

    fun classify(lowestHz: Float, highestHz: Float): ClassificationResult {
        var bestType = "Unknown"
        var bestScore = Float.NEGATIVE_INFINITY
        var bestExplanation = ""

        for (vr in VOICE_RANGES) {
            val overlapSpan = maxOf(0f, minOf(highestHz, vr.highHz) - maxOf(lowestHz, vr.lowHz))
            val union = maxOf(highestHz, vr.highHz) - minOf(lowestHz, vr.lowHz)
            val overlapRatio = if (union > 0) overlapSpan / union else 0f

            val userMidMidi = PitchDetector.hzToMidi((lowestHz + highestHz) / 2)
            val vrMidMidi   = PitchDetector.hzToMidi((vr.lowHz + vr.highHz) / 2)
            val centerDist  = Math.abs(userMidMidi - vrMidMidi)

            val score = overlapRatio - 0.05f * centerDist

            if (score > bestScore) {
                bestScore = score
                bestType = vr.name
                bestExplanation = "${vr.name}: overlap=${(overlapRatio*100).toInt()}%, " +
                    "center offset=${String.format("%.1f", centerDist)} semitones, " +
                    "score=${String.format("%.3f", score)}"
            }
        }

        val explanation = buildString {
            append("Classified as $bestType.\n")
            append("Singing range: ${PitchDetector.hzToNoteName(lowestHz)} – ${PitchDetector.hzToNoteName(highestHz)}.\n")
            append("Best match: $bestExplanation.\n")
            append("Scoring: overlap ratio minus 0.05×semitone distance from voice center.")
        }

        return ClassificationResult(bestType, explanation, bestScore)
    }
}
