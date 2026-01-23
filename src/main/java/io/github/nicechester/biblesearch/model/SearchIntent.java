package io.github.nicechester.biblesearch.model;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents the detected intent of a search query.
 * Used to route queries to the appropriate search method (keyword vs semantic).
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

    // Patterns that indicate keyword search intent
    private static final List<Pattern> KEYWORD_PATTERNS = List.of(
        // Korean patterns for "word X appears/is mentioned"
        Pattern.compile("[\"'](.+?)[\"']"),  // Quoted text: "가사" or '가사'
        Pattern.compile("(.+?)(?:라는|이라는)\\s*(?:단어|말|지명|이름|인물|사람|곳|장소)"),  // X라는 지명/이름/단어
        Pattern.compile("(.+?)(?:가|이)\\s*(?:나오는|나온|등장하는|언급된|포함된)"),  // X가 나오는/등장하는
        Pattern.compile("(.+?)(?:을|를)\\s*(?:포함한|포함하는|담은|담고)"),  // X를 포함한
        Pattern.compile("(.+?)(?:에 대해|에 관해|에 관한)\\s*(?:말하는|언급하는)"),  // X에 대해 말하는
        
        // English patterns
        Pattern.compile("(?:containing|with|mentions?|about)\\s+[\"']?(.+?)[\"']?$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:the word|the name|the place)\\s+[\"']?(.+?)[\"']?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("verses?\\s+(?:with|containing|mentioning)\\s+[\"']?(.+?)[\"']?", Pattern.CASE_INSENSITIVE)
    );

    // Patterns that indicate semantic search intent
    private static final List<Pattern> SEMANTIC_PATTERNS = List.of(
        // Korean patterns for concept/meaning search
        Pattern.compile("(?:에 대한|에 관한|에 대해|관련된)\\s*(?:말씀|구절|성경|내용)"),
        Pattern.compile("(?:의미|뜻|교훈)"),
        Pattern.compile("(?:어떻게|왜|무엇)"),
        
        // English patterns
        Pattern.compile("(?:about|regarding|concerning)\\s+(?:love|faith|hope|forgiveness)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:what does|how does|why does)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:meaning of|verses about|passages about)", Pattern.CASE_INSENSITIVE)
    );

    /**
     * Detect the intent of a search query.
     */
    public static SearchIntent detect(String query) {
        if (query == null || query.isBlank()) {
            return new SearchIntent(IntentType.SEMANTIC, null, query, "Empty query defaults to semantic");
        }

        String trimmed = query.trim();

        // 1. Check for explicit keyword patterns first
        for (Pattern pattern : KEYWORD_PATTERNS) {
            Matcher matcher = pattern.matcher(trimmed);
            if (matcher.find()) {
                String keyword = matcher.group(1).trim();
                // Clean up the keyword
                keyword = keyword.replaceAll("[\"'\\s]+$", "").trim();
                if (!keyword.isEmpty() && keyword.length() <= 20) {
                    return new SearchIntent(
                        IntentType.KEYWORD, 
                        keyword, 
                        query,
                        "Pattern matched: looking for keyword '" + keyword + "'"
                    );
                }
            }
        }

        // 2. Check for semantic patterns
        for (Pattern pattern : SEMANTIC_PATTERNS) {
            if (pattern.matcher(trimmed).find()) {
                return new SearchIntent(
                    IntentType.SEMANTIC,
                    null,
                    query,
                    "Semantic pattern detected: conceptual search"
                );
            }
        }

        // 3. Short queries (1-3 words, likely a keyword/name)
        String[] words = trimmed.split("\\s+");
        if (words.length <= 2) {
            // Check if it looks like a proper noun or specific term
            boolean looksLikeKeyword = trimmed.length() <= 10 || 
                                       !trimmed.matches(".*[가-힣].*\\s+[가-힣].*") || // Not a Korean phrase
                                       trimmed.matches("^[A-Z][a-z]+$"); // Capitalized English word
            
            if (looksLikeKeyword) {
                return new SearchIntent(
                    IntentType.HYBRID,
                    trimmed,
                    query,
                    "Short query: trying both keyword and semantic search"
                );
            }
        }

        // 4. Default to semantic for longer, natural language queries
        return new SearchIntent(
            IntentType.SEMANTIC,
            null,
            query,
            "Natural language query: using semantic search"
        );
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
