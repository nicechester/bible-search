package io.github.nicechester.biblesearch.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response model for Bible search results.
 */
@Data
@Builder
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
