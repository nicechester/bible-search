package io.github.nicechester.biblesearch.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.nicechester.biblesearch.model.ContextResult;
import io.github.nicechester.biblesearch.model.SearchIntent;
import io.github.nicechester.biblesearch.model.SearchIntent.IntentType;
import io.github.nicechester.biblesearch.model.SearchResponse;
import io.github.nicechester.biblesearch.model.VerseResult;
import io.github.nicechester.biblesearch.service.BibleDataService.VerseData;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bible Search Service implementing Two-Stage Retrieval:
 * 
 * Stage 1: Bi-Encoder (Fast Candidate Retrieval)
 *   - Uses All-MiniLM-L6-V2 to embed the query
 *   - Performs cosine similarity search against pre-indexed verses
 *   - Returns top N candidates
 * 
 * Stage 2: Re-ranking (Precision Scoring)
 *   - Applies additional scoring/filtering based on:
 *     - Text length normalization
 *     - Keyword boost
 *     - Version preference
 *   - Returns final sorted results
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BibleSearchService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final BibleDataService bibleDataService;
    private final IntentClassifierService intentClassifier;
    private final ContextClassifierService contextClassifier;
    private final EmbeddingStoreService embeddingStoreService;

    @Value("${bible.search.candidate-count:50}")
    private int candidateCount;

    @Value("${bible.search.result-count:5}")
    private int resultCount;

    @Value("${bible.search.min-score:0.3}")
    private double minScore;

    // Map from embedding store segment to verse key for fast lookup
    private final Map<String, String> segmentToVerseKey = new HashMap<>();
    
    // Flag to track if embeddings were loaded from GCS
    private boolean loadedFromGcs = false;

    @PostConstruct
    public void initializeEmbeddings() {
        log.info("Initializing Bible verse embeddings...");
        long startTime = System.currentTimeMillis();

        List<VerseData> verses = bibleDataService.getAllVerses();
        if (verses.isEmpty()) {
            log.warn("No verses to index!");
            return;
        }

        // Check if embeddings were already loaded from GCS by EmbeddingConfig
        // We can detect this by checking if the store already has entries
        boolean storeHasEntries = checkStoreHasEntries();
        
        if (storeHasEntries) {
            // Store was loaded from GCS - just build the lookup map
            log.info("Embeddings already loaded (from GCS), building lookup map...");
            buildLookupMap(verses);
            loadedFromGcs = true;
        } else {
            // Generate embeddings from scratch
            generateAndIndexEmbeddings(verses);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Embedding initialization completed in {}ms ({} verses)", duration, segmentToVerseKey.size());
    }

    /**
     * Check if the embedding store already has entries (loaded from GCS).
     */
    private boolean checkStoreHasEntries() {
        try {
            // Try a dummy search to see if store has entries
            Embedding dummyEmbedding = embeddingModel.embed("test").content();
            var result = embeddingStore.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(dummyEmbedding)
                    .maxResults(1)
                    .build());
            return !result.matches().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Build the lookup map from verses (when embeddings are already loaded).
     */
    private void buildLookupMap(List<VerseData> verses) {
        for (VerseData verse : verses) {
            String embeddingText = verse.toEmbeddingText();
            segmentToVerseKey.put(embeddingText, verse.getKey());
        }
    }

    /**
     * Generate embeddings for all verses and optionally save to GCS.
     */
    private void generateAndIndexEmbeddings(List<VerseData> verses) {
        // Create text segments and embeddings for each verse
        List<TextSegment> segments = new ArrayList<>();
        List<String> verseKeys = new ArrayList<>();

        for (VerseData verse : verses) {
            String embeddingText = verse.toEmbeddingText();
            TextSegment segment = TextSegment.from(embeddingText);
            segments.add(segment);
            verseKeys.add(verse.getKey());
        }

        // Generate embeddings in batches
        log.info("Generating embeddings for {} verses (this may take a few minutes)...", segments.size());
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // Store embeddings and build lookup map
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Embedding embedding = embeddings.get(i);
            embeddingStore.add(embedding, segment);
            segmentToVerseKey.put(segment.text(), verseKeys.get(i));
        }

        log.info("Indexed {} verses into embedding store", segments.size());

        // Save to GCS for future fast loading
        if (embeddingStoreService.isEnabled()) {
            log.info("Saving embeddings to GCS for future fast startup...");
            embeddingStoreService.saveToGcs(embeddingStore);
        }
    }

    /**
     * Perform search with automatic intent detection and context extraction.
     * 
     * <p>Flow:
     * <ol>
     *   <li>Extract context (book scope) from query using ContextClassifierService</li>
     *   <li>Classify intent (keyword/semantic/hybrid) using cleaned query</li>
     *   <li>Perform search with context filtering</li>
     * </ol>
     */
    public SearchResponse search(String query, Integer maxResults, Double minScoreThreshold, String versionFilter) {
        long startTime = System.currentTimeMillis();

        try {
            int resultsToReturn = maxResults != null ? maxResults : resultCount;
            double threshold = minScoreThreshold != null ? minScoreThreshold : minScore;

            // Step 1: Extract context (book scope) from query
            ContextResult context = contextClassifier.extract(query);
            String searchQuery = context.getSearchQuery();
            
            log.info("Context extracted: {} -> '{}' (type: {}, books: {})", 
                     query, searchQuery, context.contextType(), 
                     context.bookShorts() != null ? context.bookShorts() : "all");

            // Step 2: Classify user intent using the cleaned query
            SearchIntent intent = intentClassifier.classify(searchQuery);
            log.info("Classified intent: {} for query '{}' (keyword: {}) - {}", 
                     intent.type(), searchQuery, intent.extractedKeyword(), intent.reason());

            List<VerseResult> results;

            // Step 3: Perform search with context filtering
            switch (intent.type()) {
                case KEYWORD:
                    results = performKeywordSearch(intent.extractedKeyword(), versionFilter, context, resultsToReturn);
                    break;
                case HYBRID:
                    results = performHybridSearch(searchQuery, intent.extractedKeyword(), threshold, versionFilter, context, resultsToReturn);
                    break;
                case SEMANTIC:
                default:
                    results = performSemanticSearch(searchQuery, threshold, versionFilter, context, resultsToReturn);
                    break;
            }

            long searchTime = System.currentTimeMillis() - startTime;
            log.info("Search completed in {}ms: '{}' [{}] -> {} results (context: {})", 
                     searchTime, query, intent.type(), results.size(), 
                     context.hasContext() ? context.contextDescription() : "none");

            return SearchResponse.success(query, results, searchTime, intent, context);

        } catch (Exception e) {
            log.error("Search failed for query: {}", query, e);
            return SearchResponse.error(query, e.getMessage());
        }
    }

    /**
     * Perform keyword-only search (exact text match).
     */
    private List<VerseResult> performKeywordSearch(String keyword, String versionFilter, 
                                                    ContextResult context, int maxResults) {
        log.debug("Performing keyword search for: {} (context: {})", keyword,
                 context.hasContext() ? context.contextDescription() : "none");
        
        List<VerseData> matches = bibleDataService.searchByKeyword(keyword);
        
        return matches.stream()
            .filter(v -> matchesVersion(v.getVersion(), versionFilter))
            .filter(v -> context.matchesVerse(v.getBookShort(), v.getTestament()))
            .limit(maxResults)
            .map(v -> bibleDataService.toVerseResult(v, 1.0)
                .toBuilder()
                .rerankedScore(1.0)  // Exact match = perfect score
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Perform hybrid search: combines keyword and semantic results.
     */
    private List<VerseResult> performHybridSearch(String query, String keyword, double threshold, 
                                                   String versionFilter, ContextResult context, int maxResults) {
        log.debug("Performing hybrid search: keyword='{}', query='{}' (context: {})", 
                 keyword, query, context.hasContext() ? context.contextDescription() : "none");
        
        // Get keyword matches first (these are exact matches, high priority)
        List<VerseData> keywordMatches = bibleDataService.searchByKeyword(keyword);
        Set<String> keywordMatchKeys = keywordMatches.stream()
            .filter(v -> context.matchesVerse(v.getBookShort(), v.getTestament()))
            .map(VerseData::getKey)
            .collect(Collectors.toSet());
        
        // Get semantic matches
        List<ScoredVerse> semanticCandidates = retrieveCandidates(query, candidateCount);
        
        // Build result list: keyword matches first, then semantic matches that aren't duplicates
        List<VerseResult> results = new ArrayList<>();
        
        // Add keyword matches with boosted score
        keywordMatches.stream()
            .filter(v -> matchesVersion(v.getVersion(), versionFilter))
            .filter(v -> context.matchesVerse(v.getBookShort(), v.getTestament()))
            .limit(maxResults)
            .forEach(v -> results.add(
                bibleDataService.toVerseResult(v, 1.0)
                    .toBuilder()
                    .rerankedScore(1.0)
                    .build()
            ));
        
        // Add semantic matches that aren't already in keyword results
        if (results.size() < maxResults) {
            String[] queryWords = query.toLowerCase().split("\\s+");
            
            semanticCandidates.stream()
                .filter(sv -> !keywordMatchKeys.contains(sv.verse.getKey()))
                .filter(sv -> matchesVersion(sv.verse.getVersion(), versionFilter))
                .filter(sv -> context.matchesVerse(sv.verse.getBookShort(), sv.verse.getTestament()))
                .map(sv -> {
                    double rerankedScore = calculateRerankedScore(sv, queryWords);
                    return new ScoredVerse(sv.verse, sv.score, rerankedScore);
                })
                .filter(sv -> sv.rerankedScore >= threshold)
                .sorted((a, b) -> Double.compare(b.rerankedScore, a.rerankedScore))
                .limit(maxResults - results.size())
                .forEach(sv -> results.add(
                    bibleDataService.toVerseResult(sv.verse, sv.score)
                        .toBuilder()
                        .rerankedScore(sv.rerankedScore)
                        .build()
                ));
        }
        
        return results;
    }

    /**
     * Perform semantic-only search (original two-stage retrieval).
     */
    private List<VerseResult> performSemanticSearch(String query, double threshold, 
                                                     String versionFilter, ContextResult context, int maxResults) {
        log.debug("Performing semantic search for: {} (context: {})", query,
                 context.hasContext() ? context.contextDescription() : "none");
        
        // Stage 1: Bi-Encoder Candidate Retrieval
        List<ScoredVerse> candidates = retrieveCandidates(query, candidateCount);

        if (candidates.isEmpty()) {
            return List.of();
        }

        // Stage 2: Re-ranking and Filtering
        return rerankAndFilter(candidates, query, threshold, versionFilter, context, maxResults);
    }

    /**
     * Stage 1: Fast candidate retrieval using bi-encoder embeddings.
     */
    private List<ScoredVerse> retrieveCandidates(String query, int topK) {
        // Embed the query
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        // Search the embedding store
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(topK)
            .minScore(0.1) // Low threshold for candidates
            .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequest).matches();

        // Convert matches to scored verses
        List<ScoredVerse> candidates = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            String segmentText = match.embedded().text();
            String verseKey = segmentToVerseKey.get(segmentText);
            
            if (verseKey != null) {
                bibleDataService.getVerseByKey(verseKey).ifPresent(verse -> {
                    candidates.add(new ScoredVerse(verse, match.score()));
                });
            }
        }

        return candidates;
    }

    // Version aliases for flexible filtering
    private static final Map<String, Set<String>> VERSION_ALIASES = Map.of(
        "KRV", Set.of("KRV", "개역개정", "개역한글", "krv"),
        "ASV", Set.of("ASV", "asv", "American Standard Version"),
        "개역개정", Set.of("KRV", "개역개정", "개역한글", "krv")
    );

    /**
     * Check if a verse version matches the requested filter.
     */
    private boolean matchesVersion(String verseVersion, String filterVersion) {
        if (filterVersion == null || filterVersion.isBlank()) {
            return true; // No filter = match all
        }
        
        // Direct match (case-insensitive)
        if (filterVersion.equalsIgnoreCase(verseVersion)) {
            return true;
        }
        
        // Check aliases
        Set<String> aliases = VERSION_ALIASES.get(filterVersion.toUpperCase());
        if (aliases != null && aliases.stream().anyMatch(a -> a.equalsIgnoreCase(verseVersion))) {
            return true;
        }
        
        // Check reverse: if verseVersion has aliases that include filterVersion
        Set<String> verseAliases = VERSION_ALIASES.get(verseVersion);
        if (verseAliases != null && verseAliases.stream().anyMatch(a -> a.equalsIgnoreCase(filterVersion))) {
            return true;
        }
        
        return false;
    }

    /**
     * Stage 2: Re-rank candidates and apply filters.
     * 
     * Re-ranking factors:
     * 1. Original embedding score (semantic similarity)
     * 2. Keyword boost (if query words appear in verse text)
     * 3. Length normalization (prefer concise, focused verses)
     * 4. Version filter (if specified)
     * 5. Context filter (book scope)
     */
    private List<VerseResult> rerankAndFilter(
            List<ScoredVerse> candidates,
            String query,
            double minScore,
            String versionFilter,
            ContextResult context,
            int maxResults) {

        String[] queryWords = query.toLowerCase().split("\\s+");

        return candidates.stream()
            // Filter by version if specified (with alias support)
            .filter(sv -> matchesVersion(sv.verse.getVersion(), versionFilter))
            // Filter by context (book scope)
            .filter(sv -> context.matchesVerse(sv.verse.getBookShort(), sv.verse.getTestament()))
            // Calculate re-ranked score
            .map(sv -> {
                double rerankedScore = calculateRerankedScore(sv, queryWords);
                return new ScoredVerse(sv.verse, sv.score, rerankedScore);
            })
            // Filter by minimum score
            .filter(sv -> sv.rerankedScore >= minScore)
            // Sort by re-ranked score (descending)
            .sorted((a, b) -> Double.compare(b.rerankedScore, a.rerankedScore))
            // Limit results
            .limit(maxResults)
            // Convert to VerseResult
            .map(sv -> bibleDataService.toVerseResult(sv.verse, sv.score)
                .toBuilder()
                .rerankedScore(sv.rerankedScore)
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Calculate re-ranked score combining multiple signals.
     */
    private double calculateRerankedScore(ScoredVerse sv, String[] queryWords) {
        double baseScore = sv.score;
        String verseText = sv.verse.getText().toLowerCase();

        // Keyword boost: +0.1 for each query word found in verse
        double keywordBoost = 0.0;
        for (String word : queryWords) {
            if (word.length() > 2 && verseText.contains(word)) {
                keywordBoost += 0.05;
            }
        }
        keywordBoost = Math.min(keywordBoost, 0.2); // Cap at 0.2

        // Length penalty: slight penalty for very long verses
        double lengthFactor = 1.0;
        int textLength = sv.verse.getText().length();
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

    /**
     * Internal class for holding verse with scores.
     */
    private static class ScoredVerse {
        final VerseData verse;
        final double score;
        final double rerankedScore;

        ScoredVerse(VerseData verse, double score) {
            this(verse, score, score);
        }

        ScoredVerse(VerseData verse, double score, double rerankedScore) {
            this.verse = verse;
            this.score = score;
            this.rerankedScore = rerankedScore;
        }
    }

    /**
     * Get service statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("indexedSegments", segmentToVerseKey.size());
        stats.put("candidateCount", candidateCount);
        stats.put("resultCount", resultCount);
        stats.put("minScore", minScore);
        stats.put("intentClassifier", intentClassifier.getStats());
        stats.put("contextClassifier", contextClassifier.getStats());
        stats.put("gcsEnabled", embeddingStoreService.isEnabled());
        stats.put("gcsPath", embeddingStoreService.getGcsPath());
        stats.put("loadedFromGcs", loadedFromGcs);
        stats.putAll(bibleDataService.getStatistics());
        return stats;
    }
}
