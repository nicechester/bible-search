package io.github.nicechester.biblesearch.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.nicechester.biblesearch.model.SearchIntent;
import io.github.nicechester.biblesearch.model.SearchIntent.IntentType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intent classifier that uses embedding similarity to detect search intent.
 * 
 * Instead of rigid regex patterns, this classifier:
 * 1. Pre-computes embeddings for prototype phrases of each intent type
 * 2. At query time, embeds the query and compares to prototypes
 * 3. Returns the intent with highest average similarity
 * 
 * This is essentially "few-shot classification" using embeddings.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifierService {

    private final EmbeddingModel embeddingModel;

    // Pre-computed embeddings for prototypes
    private final List<Embedding> keywordPrototypeEmbeddings = new ArrayList<>();
    private final List<Embedding> semanticPrototypeEmbeddings = new ArrayList<>();

    // Prototype phrases for KEYWORD intent (exact word match queries)
    private static final List<String> KEYWORD_PROTOTYPES = List.of(
        // Korean patterns - looking for specific words
        "가사라는 지명이 나오는 구절",
        "모세가 등장하는 구절을 찾아줘",
        "다윗이라는 이름이 나오는 성경 구절",
        "아브라함이 언급된 부분",
        "예루살렘이 나오는 곳",
        "바울이라는 단어가 포함된 구절",
        "베드로가 나오는 성경",
        "시온이라는 지명",
        "갈릴리가 언급되는 구절",
        "여리고가 등장하는",
        
        // English patterns - looking for specific words
        "verses containing the word shepherd",
        "find verses that mention Moses",
        "passages where David appears",
        "verses with the name Abraham",
        "scriptures mentioning Jerusalem",
        "verses that contain the word love",
        "find where Paul is mentioned",
        "passages with the word faith",
        "verses including the term righteousness",
        "scriptures containing Galilee"
    );

    // Prototype phrases for SEMANTIC intent (meaning-based queries)
    private static final List<String> SEMANTIC_PROTOTYPES = List.of(
        // Korean patterns - conceptual/thematic
        "사랑에 대한 말씀",
        "용서에 관한 구절",
        "믿음의 의미를 알려주는 성경",
        "힘든 시간에 위로가 되는 말씀",
        "하나님의 사랑을 느낄 수 있는 구절",
        "소망과 희망에 대해",
        "감사에 관련된 성경 구절",
        "평안을 주는 말씀",
        "지혜로운 삶에 대한 가르침",
        "겸손함에 대해 말하는 구절",
        
        // English patterns - conceptual/thematic
        "verses about God's love",
        "what does the Bible say about forgiveness",
        "comfort in times of suffering",
        "passages about faith and trust",
        "scriptures on hope and encouragement",
        "teachings about wisdom",
        "verses related to peace and rest",
        "passages concerning eternal life",
        "what the Bible teaches about humility",
        "scriptures about gratitude and thanksgiving"
    );

    // Patterns for extracting keywords (still needed for keyword extraction)
    private static final List<Pattern> KEYWORD_EXTRACTION_PATTERNS = List.of(
        Pattern.compile("[\"'](.+?)[\"']"),
        Pattern.compile("(.+?)(?:라는|이라는)\\s*(?:단어|말|지명|이름|인물|사람|곳|장소)"),
        Pattern.compile("(.+?)(?:가|이)\\s*(?:나오는|나온|등장하는|언급된|포함된)"),
        Pattern.compile("(.+?)(?:을|를)\\s*(?:포함한|포함하는|담은|담고)"),
        Pattern.compile("(?:containing|with|mentions?)\\s+(?:the\\s+word\\s+)?[\"']?([\\w가-힣]+)[\"']?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:the word|the name|the place)\\s+[\"']?([\\w가-힣]+)[\"']?", Pattern.CASE_INSENSITIVE)
    );

    // Similarity thresholds
    private static final double KEYWORD_THRESHOLD = 0.45;  // Minimum similarity to classify as KEYWORD
    private static final double SEMANTIC_THRESHOLD = 0.45; // Minimum similarity to classify as SEMANTIC
    private static final double DIFFERENCE_THRESHOLD = 0.05; // Minimum difference to prefer one over other

    @PostConstruct
    public void initializePrototypes() {
        log.info("Initializing intent classifier with {} keyword and {} semantic prototypes",
                KEYWORD_PROTOTYPES.size(), SEMANTIC_PROTOTYPES.size());
        
        long startTime = System.currentTimeMillis();

        // Pre-compute keyword prototype embeddings
        for (String prototype : KEYWORD_PROTOTYPES) {
            keywordPrototypeEmbeddings.add(embeddingModel.embed(prototype).content());
        }

        // Pre-compute semantic prototype embeddings
        for (String prototype : SEMANTIC_PROTOTYPES) {
            semanticPrototypeEmbeddings.add(embeddingModel.embed(prototype).content());
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Intent classifier initialized in {}ms", duration);
    }

    /**
     * Classify the intent of a search query using embedding similarity.
     */
    public SearchIntent classify(String query) {
        if (query == null || query.isBlank()) {
            return new SearchIntent(IntentType.SEMANTIC, null, query, 
                "Empty query defaults to semantic");
        }

        String trimmed = query.trim();

        // Only very short single-word queries default to HYBRID
        // Let the embedding classifier handle multi-word queries
        String[] words = trimmed.split("\\s+");
        if (words.length == 1 && trimmed.length() <= 6) {
            return new SearchIntent(IntentType.HYBRID, trimmed, query,
                String.format("Single short word (%d chars): using hybrid search", trimmed.length()));
        }

        // Embed the query
        Embedding queryEmbedding = embeddingModel.embed(trimmed).content();

        // Calculate average similarity to keyword prototypes
        double keywordSimilarity = calculateAverageSimilarity(queryEmbedding, keywordPrototypeEmbeddings);

        // Calculate average similarity to semantic prototypes
        double semanticSimilarity = calculateAverageSimilarity(queryEmbedding, semanticPrototypeEmbeddings);

        log.debug("Intent scores for '{}': keyword={:.3f}, semantic={:.3f}", 
                 trimmed, keywordSimilarity, semanticSimilarity);

        // Decision logic
        double difference = keywordSimilarity - semanticSimilarity;

        if (keywordSimilarity > KEYWORD_THRESHOLD && difference > DIFFERENCE_THRESHOLD) {
            // Clearly a keyword search
            String extractedKeyword = extractKeyword(trimmed);
            return new SearchIntent(IntentType.KEYWORD, extractedKeyword, query,
                String.format("Keyword intent detected (score: %.0f%% vs %.0f%%)", 
                             keywordSimilarity * 100, semanticSimilarity * 100));
        } else if (semanticSimilarity > SEMANTIC_THRESHOLD && -difference > DIFFERENCE_THRESHOLD) {
            // Clearly a semantic search
            return new SearchIntent(IntentType.SEMANTIC, null, query,
                String.format("Semantic intent detected (score: %.0f%% vs %.0f%%)", 
                             semanticSimilarity * 100, keywordSimilarity * 100));
        } else {
            // Ambiguous → use hybrid
            String extractedKeyword = extractKeyword(trimmed);
            return new SearchIntent(IntentType.HYBRID, extractedKeyword, query,
                String.format("Ambiguous intent (keyword: %.0f%%, semantic: %.0f%%): using hybrid", 
                             keywordSimilarity * 100, semanticSimilarity * 100));
        }
    }

    /**
     * Calculate average cosine similarity between query and prototype embeddings.
     */
    private double calculateAverageSimilarity(Embedding query, List<Embedding> prototypes) {
        if (prototypes.isEmpty()) return 0.0;

        double totalSimilarity = 0.0;
        for (Embedding prototype : prototypes) {
            totalSimilarity += cosineSimilarity(query.vector(), prototype.vector());
        }
        return totalSimilarity / prototypes.size();
    }

    /**
     * Calculate cosine similarity between two vectors.
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0.0 : dotProduct / denominator;
    }

    /**
     * Extract the keyword/name/place from a query.
     */
    private String extractKeyword(String query) {
        for (Pattern pattern : KEYWORD_EXTRACTION_PATTERNS) {
            Matcher matcher = pattern.matcher(query);
            if (matcher.find()) {
                String keyword = matcher.group(1).trim();
                keyword = keyword.replaceAll("[\"'\\s]+$", "").trim();
                if (!keyword.isEmpty() && keyword.length() <= 20) {
                    return keyword;
                }
            }
        }
        
        // Fallback: if query is short enough, use it as the keyword
        String[] words = query.split("\\s+");
        if (words.length <= 3) {
            // Try to find the main noun (usually the first significant word)
            for (String word : words) {
                if (word.length() >= 2 && !isStopWord(word)) {
                    return word;
                }
            }
        }
        
        return null;
    }

    /**
     * Check if a word is a common stop word (should not be used as keyword).
     */
    private boolean isStopWord(String word) {
        String lower = word.toLowerCase();
        return List.of(
            // English stop words
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "find", "search", "show", "get", "verses", "verse", "passages",
            "containing", "with", "about", "for", "in", "on", "at",
            // Korean stop words
            "를", "을", "이", "가", "에", "의", "와", "과", "로", "으로",
            "구절", "말씀", "성경", "찾아", "줘", "주세요"
        ).contains(lower);
    }

    /**
     * Get classifier statistics for debugging.
     */
    public String getStats() {
        return String.format("IntentClassifier: %d keyword prototypes, %d semantic prototypes",
                keywordPrototypeEmbeddings.size(), semanticPrototypeEmbeddings.size());
    }
}
