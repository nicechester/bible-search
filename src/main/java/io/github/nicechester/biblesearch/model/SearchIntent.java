package io.github.nicechester.biblesearch.model;

/**
 * Represents the classified intent of a search query.
 * Used to route queries to the appropriate search method (keyword vs semantic).
 * 
 * Intent classification is performed by IntentClassifierService using
 * embedding similarity to prototype phrases.
 */
public record SearchIntent(
    IntentType type,
    String extractedKeyword,
    String originalQuery,
    String reason
) {
    public enum IntentType {
        /** User wants exact keyword/word match (e.g., "가사가 나오는 구절") */
        KEYWORD,
        /** User wants semantic/meaning-based search (e.g., "사랑에 대한 말씀") */
        SEMANTIC,
        /** Use both methods and combine results */
        HYBRID
    }

    /**
     * Check if this intent requires keyword search.
     */
    public boolean needsKeywordSearch() {
        return type == IntentType.KEYWORD || type == IntentType.HYBRID;
    }

    /**
     * Check if this intent requires semantic search.
     */
    public boolean needsSemanticSearch() {
        return type == IntentType.SEMANTIC || type == IntentType.HYBRID;
    }
}
