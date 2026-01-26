package io.github.nicechester.biblesearch.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

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
     * Detected context type: NONE, TESTAMENT, BOOK_GROUP, SINGLE_BOOK, MULTIPLE_BOOKS
     */
    private String detectedContextType;
    
    /**
     * Human-readable description of detected context (e.g., "사복음서", "로마서")
     */
    private String detectedContext;
    
    /**
     * List of book short names being filtered (e.g., ["마", "막", "눅", "요"])
     */
    private List<String> contextBooks;
    
    /**
     * The query used for actual search (with context keywords removed)
     */
    private String searchQuery;
    
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
     * Create a successful response with intent and context info
     */
    public static SearchResponse success(String query, List<VerseResult> results, long searchTimeMs, 
                                         SearchIntent intent, ContextResult context) {
        SearchResponseBuilder builder = SearchResponse.builder()
            .query(query)
            .results(results)
            .totalResults(results.size())
            .searchTimeMs(searchTimeMs)
            .success(true)
            .searchMethod(intent.type().name())
            .extractedKeyword(intent.extractedKeyword())
            .intentReason(intent.reason())
            .searchQuery(context.getSearchQuery());
        
        // Add context info if present
        if (context.hasContext()) {
            builder.detectedContextType(context.contextType().name())
                   .detectedContext(context.contextDescription())
                   .contextBooks(context.bookShorts());
        }
        
        return builder.build();
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
