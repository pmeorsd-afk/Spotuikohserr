package com.music.spotui.playback

import com.music.spotui.data.entity.SongsModel
import kotlin.math.abs
import kotlin.math.max

/**
 * Intelligent matcher between Spotify podcast episode metadata and RSS feed items.
 * Evaluates multiple signals (normalized title similarity, duration proximity, episode numbering)
 * to reliably select the correct audio enclosure without blindly picking the first item
 * or assuming Spotify ID equals RSS GUID.
 */
object PodcastEpisodeMatcher {

    private const val MIN_MATCH_SCORE = 40.0

    /**
     * Finds the best matching [PodcastRssEpisode] for the target [SongsModel].
     * Returns null if no candidate passes the confidence threshold or has a valid enclosure.
     */
    fun match(target: SongsModel, candidates: List<PodcastRssEpisode>): PodcastRssEpisode? {
        val eligible = candidates.filter { !it.enclosureUrl.isNullOrBlank() }
        if (eligible.isEmpty()) return null

        var bestCandidate: PodcastRssEpisode? = null
        var bestScore = -1.0

        for (candidate in eligible) {
            val score = scoreCandidate(target, candidate)
            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidate
            }
        }

        return if (bestScore >= MIN_MATCH_SCORE) bestCandidate else null
    }

    /**
     * Scores a candidate episode against the target [SongsModel].
     * Total score ranges from 0.0 to ~100.0+.
     */
    fun scoreCandidate(target: SongsModel, candidate: PodcastRssEpisode): Double {
        val targetNormTitle = normalizeTitle(target.title)
        val candidateNormTitle = normalizeTitle(candidate.title)

        if (targetNormTitle.isBlank() || candidateNormTitle.isBlank()) return 0.0

        var score = 0.0

        // 1. Title Similarity (up to 60 points)
        if (targetNormTitle == candidateNormTitle) {
            score += 60.0
        } else if (targetNormTitle.contains(candidateNormTitle) || candidateNormTitle.contains(targetNormTitle)) {
            val ratio = minOf(targetNormTitle.length, candidateNormTitle.length).toDouble() /
                    maxOf(targetNormTitle.length, candidateNormTitle.length)
            score += 45.0 * ratio
        } else {
            val targetTokens = tokenize(targetNormTitle)
            val candidateTokens = tokenize(candidateNormTitle)
            if (targetTokens.isNotEmpty() && candidateTokens.isNotEmpty()) {
                val commonTokens = targetTokens.intersect(candidateTokens)
                val tokenRatio = commonTokens.size.toDouble() / max(targetTokens.size, candidateTokens.size)
                score += tokenRatio * 50.0
            }
        }

        // 2. Episode Number Disambiguation (+20 bonus or -30 penalty)
        val targetEpNum = extractEpisodeNumber(target.title)
        val candidateEpNum = extractEpisodeNumber(candidate.title)
        if (targetEpNum != null && candidateEpNum != null) {
            if (targetEpNum == candidateEpNum) {
                score += 20.0
            } else {
                score -= 30.0 // Strong penalty if episode numbers contradict!
            }
        }

        // 3. Duration Signal (up to +35 bonus or -35 penalty)
        val targetDurationMs = target.durationMs.toLong()
        val candidateDurationMs = candidate.durationMs

        if (targetDurationMs > 0L && candidateDurationMs != null && candidateDurationMs > 0L) {
            val diffSec = abs(targetDurationMs - candidateDurationMs) / 1000L
            when {
                diffSec <= 15L -> score += 35.0
                diffSec <= 60L -> score += 25.0
                diffSec <= 180L -> score += 15.0
                diffSec <= 300L -> score += 5.0
                diffSec > 600L -> score -= 35.0 // Vast duration difference (>10m) penalizes generic title matches!
            }
        }

        return score
    }

    /**
     * Normalizes a title for robust comparison:
     * - Decodes HTML entities
     * - Lowercase
     * - Trims & collapses whitespace
     * - Normalizes Hebrew quotes and punctuation
     */
    fun normalizeTitle(title: String): String {
        return PodcastRssParser.decodeHtmlEntities(title)
            .lowercase()
            .replace('״', '"')
            .replace('׳', '\'')
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("""[^\p{L}\p{N}\s-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Splits normalized text into meaningful tokens (ignoring single character noise).
     */
    private fun tokenize(normalized: String): Set<String> {
        return normalized.split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 || it.all { ch -> ch.isDigit() } }
            .toSet()
    }

    /**
     * Extracts an episode number if present in common Hebrew/English formats:
     * - "פרק 55"
     * - "פרק #55"
     * - "ep 55"
     * - "#55"
     */
    fun extractEpisodeNumber(title: String): Int? {
        val patterns = listOf(
            Regex("""(?:פרק|episode|ep|פרק מספר)[\s#:]*([0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""^#([0-9]+)\b"""),
            Regex("""\b([0-9]+)\s*[:|-]""")
        )

        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }
}
