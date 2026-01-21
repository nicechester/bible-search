package io.github.nicechester.biblesearch.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a Bible verse search result with relevance score.
 */
@Data
@Builder(toBuilder = true)
public class VerseResult {
    
    /**
     * Full reference string (e.g., "Genesis 1:1" or "창세기 1:1")
     */
    private String reference;
    
    /**
     * Book name (e.g., "Genesis" or "창세기")
     */
    private String bookName;
    
    /**
     * Short book code (e.g., "Gen")
     */
    private String bookShort;
    
    /**
     * Chapter number
     */
    private Integer chapter;
    
    /**
     * Verse number
     */
    private Integer verse;
    
    /**
     * Section title if present
     */
    private String title;
    
    /**
     * The verse text content
     */
    private String text;
    
    /**
     * Bible version (e.g., "ASV", "KRV")
     */
    private String version;
    
    /**
     * Relevance score from embedding similarity (0.0 to 1.0)
     */
    private Double score;
    
    /**
     * Re-ranked score from cross-encoder (0.0 to 1.0), if re-ranking was applied
     */
    private Double rerankedScore;
}
