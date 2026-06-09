package io.github.nicechester.biblesearch.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ONNX-based reranker using BGE Reranker v2-m3 cross-encoder model.
 * Falls back to HeuristicRerankingService if model loading fails.
 */
@Slf4j
@Service
public class OnnxRerankingService implements RerankingService, AutoCloseable {

    private final HeuristicRerankingService fallback;
    private final boolean enabled;
    private final String modelPath;
    private final String tokenizerPath;
    private final int modelMaxLength;

    private ScoringModel scoringModel;
    private boolean available = false;

    public OnnxRerankingService(
            HeuristicRerankingService fallback,
            @Value("${bible.reranker.enabled:false}") boolean enabled,
            @Value("${bible.reranker.model-path:classpath:models/bge-reranker-v2-m3/model.onnx}") String modelPath,
            @Value("${bible.reranker.tokenizer-path:classpath:models/bge-reranker-v2-m3/tokenizer.json}") String tokenizerPath,
            @Value("${bible.reranker.model-max-length:512}") int modelMaxLength) {
        this.fallback = fallback;
        this.enabled = enabled;
        this.modelPath = modelPath;
        this.tokenizerPath = tokenizerPath;
        this.modelMaxLength = modelMaxLength;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("ONNX reranker disabled");
            return;
        }

        try {
            log.info("Initializing ONNX reranker (BGE Reranker v2-m3)");

            // Resolve model and tokenizer paths
            Path resolvedModelPath = resolvePath(modelPath);
            Path resolvedTokenizerPath = resolvePath(tokenizerPath);

            if (!Files.exists(resolvedModelPath)) {
                throw new IOException("Model file not found: " + resolvedModelPath);
            }
            if (!Files.exists(resolvedTokenizerPath)) {
                throw new IOException("Tokenizer file not found: " + resolvedTokenizerPath);
            }

            log.info("Loading ONNX model from: {}", resolvedModelPath);
            log.info("Loading tokenizer from: {}", resolvedTokenizerPath);

            // Create OnnxScoringModel (expects String paths, not Path)
            this.scoringModel = new OnnxScoringModel(
                    resolvedModelPath.toString(),
                    resolvedTokenizerPath.toString(),
                    modelMaxLength
            );

            this.available = true;
            log.info("ONNX reranker initialized successfully");

        } catch (IOException e) {
            log.warn("Failed to load ONNX reranker model: {} - will use heuristic fallback", e.getMessage());
            this.available = false;
        } catch (Exception e) {
            log.warn("Error initializing ONNX reranker: {} - will use heuristic fallback", e.getMessage(), e);
            this.available = false;
        }
    }

    @PreDestroy
    public void close() {
        if (scoringModel instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Error closing ONNX scoring model: {}", e.getMessage());
            }
        }
    }

    /**
     * Rerank passages using ONNX model, with fallback to heuristic.
     */
    @Override
    public List<Double> rerank(String query, List<String> passages) {
        // Empty list or not available
        if (passages.isEmpty() || !available) {
            return passages.isEmpty() ? List.of() : fallback.rerank(query, passages);
        }

        try {
            // Convert passages to TextSegments
            List<TextSegment> segments = passages.stream()
                    .map(TextSegment::from)
                    .toList();

            // Score all passages
            List<Double> logits = scoringModel.scoreAll(segments, query).content();

            // Apply sigmoid normalization: 1.0 / (1.0 + exp(-x))
            List<Double> normalizedScores = new ArrayList<>();
            for (Double logit : logits) {
                double sigmoid = 1.0 / (1.0 + Math.exp(-logit));
                normalizedScores.add(sigmoid);
            }

            log.debug("ONNX reranker scored {} passages", passages.size());
            return normalizedScores;

        } catch (Exception e) {
            log.warn("ONNX reranking failed: {} - falling back to heuristic", e.getMessage());
            return fallback.rerank(query, passages);
        }
    }

    /**
     * Resolve a path that may be a classpath resource or file path.
     */
    private Path resolvePath(String path) throws IOException {
        if (path.startsWith("classpath:")) {
            // Extract classpath resource to temp file
            String resourcePath = path.substring("classpath:".length());
            try (var resource = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (resource == null) {
                    throw new IOException("Classpath resource not found: " + resourcePath);
                }

                Path tempFile = Files.createTempFile("bge-reranker", path.endsWith(".onnx") ? ".onnx" : ".json");
                tempFile.toFile().deleteOnExit();
                Files.copy(resource, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.debug("Extracted {} to {}", resourcePath, tempFile);
                return tempFile;
            }
        } else {
            // Regular file path
            return Path.of(path);
        }
    }
}
