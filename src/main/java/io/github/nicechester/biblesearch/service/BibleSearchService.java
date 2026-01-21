package io.github.nicechester.biblesearch.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
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

    @Value("${bible.search.candidate-count:50}")
    private int candidateCount;

    @Value("${bible.search.result-count:5}")
    private int resultCount;

    @Value("${bible.search.min-score:0.3}")
    private double minScore;

    // Map from embedding store segment to verse key for fast lookup
    private final Map<String, String> segmentToVerseKey = new HashMap<>();

    @PostConstruct
    public void initializeEmbeddings() {
        log.info("Indexing Bible verses into embedding store...");
        long startTime = System.currentTimeMillis();

        List<VerseData> verses = bibleDataService.getAllVerses();
        if (verses.isEmpty()) {
            log.warn("No verses to index!");
            return;
        }

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
        log.info("Generating embeddings for {} verses...", segments.size());
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // Store embeddings and build lookup map
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Embedding embedding = embeddings.get(i);
            String segmentId = embeddingStore.add(embedding, segment);
            segmentToVerseKey.put(segment.text(), verseKeys.get(i));
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Indexed {} verses in {}ms", segments.size(), duration);
    }

    /**
     * Perform semantic search with two-stage retrieval.
     */
    public SearchResponse search(String query, Integer maxResults, Double minScoreThreshold, String versionFilter) {
        long startTime = System.currentTimeMillis();

        try {
            int resultsToReturn = maxResults != null ? maxResults : resultCount;
            double threshold = minScoreThreshold != null ? minScoreThreshold : minScore;

            // Stage 1: Bi-Encoder Candidate Retrieval
            log.debug("Stage 1: Retrieving candidates for query: {}", query);
            List<ScoredVerse> candidates = retrieveCandidates(query, candidateCount);

            if (candidates.isEmpty()) {
                log.debug("No candidates found for query: {}", query);
                return SearchResponse.success(query, List.of(), System.currentTimeMillis() - startTime);
            }

            log.debug("Stage 1 complete: {} candidates found", candidates.size());

            // Stage 2: Re-ranking and Filtering
            log.debug("Stage 2: Re-ranking {} candidates", candidates.size());
            List<VerseResult> results = rerankAndFilter(candidates, query, threshold, versionFilter, resultsToReturn);

            long searchTime = System.currentTimeMillis() - startTime;
            log.info("Search completed in {}ms: '{}' -> {} results", searchTime, query, results.size());

            return SearchResponse.success(query, results, searchTime);

        } catch (Exception e) {
            log.error("Search failed for query: {}", query, e);
            return SearchResponse.error(query, e.getMessage());
        }
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
     */
    private List<VerseResult> rerankAndFilter(
            List<ScoredVerse> candidates,
            String query,
            double minScore,
            String versionFilter,
            int maxResults) {

        String[] queryWords = query.toLowerCase().split("\\s+");

        return candidates.stream()
            // Filter by version if specified (with alias support)
            .filter(sv -> matchesVersion(sv.verse.getVersion(), versionFilter))
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
        stats.putAll(bibleDataService.getStatistics());
        return stats;
    }
}
