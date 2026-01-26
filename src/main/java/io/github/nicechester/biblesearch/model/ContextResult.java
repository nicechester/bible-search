package io.github.nicechester.biblesearch.model;

import java.util.List;

/**
 * Result of context extraction from a search query.
 * Contains the detected book scope and the cleaned query for search.
 * 
 * <p>Examples:
 * <ul>
 *   <li>"사복음서에서 사랑이 나온 구절" → bookShorts=[마,막,눅,요], cleanedQuery="사랑이 나온 구절"</li>
 *   <li>"로마서에서 복음의 정의" → bookShorts=[롬], cleanedQuery="복음의 정의"</li>
 *   <li>"이사야, 예레미야에서 구원이 언급된" → bookShorts=[사,렘], cleanedQuery="구원이 언급된"</li>
 *   <li>"신약에서 바벨론" → testament=2, cleanedQuery="바벨론"</li>
 * </ul>
 */
public record ContextResult(
    /**
     * Type of context constraint detected
     */
    ContextType contextType,
    
    /**
     * List of book short names to filter by (e.g., ["마", "막", "눅", "요"])
     * Null if no book filter (testament only or no context)
     */
    List<String> bookShorts,
    
    /**
     * Testament filter: 1 = OT, 2 = NT, null = all
     * Used when contextType is TESTAMENT
     */
    Integer testament,
    
    /**
     * The query with context keywords removed (use this for actual search)
     */
    String cleanedQuery,
    
    /**
     * Original query before cleaning
     */
    String originalQuery,
    
    /**
     * Human-readable description of the detected context
     */
    String contextDescription,
    
    /**
     * Confidence score for context detection (0.0 - 1.0)
     */
    Double confidence
) {
    
    public enum ContextType {
        /** No context constraint - search all books */
        NONE,
        /** Testament constraint (Old or New Testament) */
        TESTAMENT,
        /** Book group constraint (e.g., Four Gospels, Pentateuch) */
        BOOK_GROUP,
        /** Single book constraint (e.g., Romans) */
        SINGLE_BOOK,
        /** Multiple specific books (e.g., Isaiah and Jeremiah) */
        MULTIPLE_BOOKS
    }
    
    /**
     * Check if this result has any context constraint.
     */
    public boolean hasContext() {
        return contextType != ContextType.NONE;
    }
    
    /**
     * Check if a verse matches this context constraint.
     * 
     * @param verseBookShort The book short name of the verse
     * @param verseTestament The testament of the verse (1=OT, 2=NT)
     * @return true if the verse matches the constraint
     */
    public boolean matchesVerse(String verseBookShort, Integer verseTestament) {
        return switch (contextType) {
            case NONE -> true;
            case TESTAMENT -> testament != null && testament.equals(verseTestament);
            case BOOK_GROUP, SINGLE_BOOK, MULTIPLE_BOOKS -> 
                bookShorts != null && bookShorts.stream()
                    .anyMatch(b -> b.equalsIgnoreCase(verseBookShort));
        };
    }
    
    /**
     * Get the query to use for search (cleaned if context was extracted).
     */
    public String getSearchQuery() {
        return cleanedQuery != null && !cleanedQuery.isBlank() ? cleanedQuery : originalQuery;
    }
    
    /**
     * Create a result with no context constraint.
     */
    public static ContextResult noContext(String query) {
        return new ContextResult(ContextType.NONE, null, null, query, query, null, 1.0);
    }
}
