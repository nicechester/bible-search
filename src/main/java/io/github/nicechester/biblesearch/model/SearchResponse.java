package io.github.nicechester.biblesearch.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response model for Bible search results.
 */
@Data
@Builder(toBuilder = true)
public class SearchResponse {
    
    /**
     * The original search query
     */
    private String query;
    
    /**
     * List of matching verses sorted by relevance score
     */
    private List<VerseResult> results;
    
    /**
     * Total number of results found
     */
    private Integer totalResults;
    
    /**
     * Time taken for the search in milliseconds
     */
    private Long searchTimeMs;
    
    /**
     * Whether the search was successful
     */
    private boolean success;
    
    /**
     * Error message if search failed
     */
    private String error;
    
    /**
     * The detected search intent type: KEYWORD, SEMANTIC, or HYBRID
     */
    private String searchMethod;
    
    /**
     * Extracted keyword if keyword search was used
     */
    private String extractedKeyword;
    
    /**
     * Explanation of why this search method was chosen
     */
    private String intentReason;
    
    /**
     * Create a successful response
     */
    public static SearchResponse success(String query, List<VerseResult> results, long searchTimeMs) {
        return SearchResponse.builder()
            .query(query)
            .results(results)
            .totalResults(results.size())
            .searchTimeMs(searchTimeMs)
            .success(true)
            .build();
    }
    
    /**
     * Create a successful response with intent info
     */
    public static SearchResponse success(String query, List<VerseResult> results, long searchTimeMs, 
                                         SearchIntent intent) {
        return SearchResponse.builder()
            .query(query)
            .results(results)
            .totalResults(results.size())
            .searchTimeMs(searchTimeMs)
            .success(true)
            .searchMethod(intent.type().name())
            .extractedKeyword(intent.extractedKeyword())
            .intentReason(intent.reason())
            .build();
    }
    
    /**
     * Create an error response
     */
    public static SearchResponse error(String query, String errorMessage) {
        return SearchResponse.builder()
            .query(query)
            .results(List.of())
            .totalResults(0)
            .success(false)
            .error(errorMessage)
            .build();
    }
}
