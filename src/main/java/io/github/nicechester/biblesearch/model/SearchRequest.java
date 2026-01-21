package io.github.nicechester.biblesearch.model;

/**
 * Request model for Bible search queries.
 */
public record SearchRequest(
    /**
     * The search query (natural language question or keyword)
     */
    String query,
    
    /**
     * Maximum number of results to return (default: 5)
     */
    Integer maxResults,
    
    /**
     * Minimum relevance score threshold (default: 0.3)
     */
    Double minScore,
    
    /**
     * Filter by Bible version: "ASV", "KRV", or null for all
     */
    String version
) {
    public SearchRequest {
        if (maxResults == null) maxResults = 5;
        if (minScore == null) minScore = 0.3;
    }
}
