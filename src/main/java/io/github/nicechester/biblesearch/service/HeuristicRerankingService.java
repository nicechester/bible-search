package io.github.nicechester.biblesearch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Heuristic reranking service using keyword and length-based scoring.
 * This is the fallback implementation that works offline without any models.
 */
@Slf4j
@Service
public class HeuristicRerankingService implements RerankingService {

    /**
     * Rerank passages using heuristic signals: keyword boost and length penalty.
     *
     * @param query Search query
     * @param passages Candidate passages to score
     * @return Normalized scores in [0.0, 1.0]
     */
    @Override
    public List<Double> rerank(String query, List<String> passages) {
        if (passages.isEmpty()) {
            return List.of();
        }

        String[] queryWords = query.toLowerCase().split("\\s+");
        List<Double> scores = new ArrayList<>();

        for (String passage : passages) {
            double score = calculateScore(passage, queryWords);
            scores.add(score);
        }

        return scores;
    }

    /**
     * Calculate heuristic score for a single passage.
     */
    private double calculateScore(String passage, String[] queryWords) {
        // Baseline score (0.5) - represents neutral relevance
        double baseScore = 0.5;
        String passageText = passage.toLowerCase();

        // Keyword boost: +0.05 for each query word found in passage
        double keywordBoost = 0.0;
        for (String word : queryWords) {
            if (word.length() > 2 && passageText.contains(word)) {
                keywordBoost += 0.05;
            }
        }
        keywordBoost = Math.min(keywordBoost, 0.2); // Cap at 0.2

        // Length penalty: slight penalty for very long verses
        double lengthFactor = 1.0;
        int textLength = passage.length();
        if (textLength > 300) {
            lengthFactor = 0.95;
        } else if (textLength > 500) {
            lengthFactor = 0.9;
        }

        // Combine scores
        double finalScore = (baseScore + keywordBoost) * lengthFactor;

        // Normalize to 0-1 range
        return Math.min(1.0, Math.max(0.0, finalScore));
    }
}
