package io.github.nicechester.biblesearch.service;

import java.util.List;

/**
 * Reranking service for refining candidate verse search results.
 * Takes a query and list of passages, returns normalized relevance scores in [0.0, 1.0].
 * Scores are returned in the same order as the input passages.
 */
public interface RerankingService {

    /**
     * Rerank passages relative to a query.
     *
     * @param query Search query
     * @param passages Candidate passages to score
     * @return List of scores in [0.0, 1.0], same length and order as passages
     */
    List<Double> rerank(String query, List<String> passages);
}
