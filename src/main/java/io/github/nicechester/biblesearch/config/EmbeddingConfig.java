package io.github.nicechester.biblesearch.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Configuration for embedding model and vector store.
 * Uses multilingual ONNX model for Korean + English semantic search.
 * 
 * Model: paraphrase-multilingual-MiniLM-L12-v2 (quantized)
 * - Supports 50+ languages including Korean
 * - 384-dimensional embeddings
 * - ONNX Runtime for local CPU inference
 */
@Slf4j
@Configuration
public class EmbeddingConfig {

    private final ResourceLoader resourceLoader;

    @Value("${bible.embedding.model-path:classpath:models/multilingual-minilm/model.onnx}")
    private String modelPath;

    @Value("${bible.embedding.tokenizer-path:classpath:models/multilingual-minilm/tokenizer.json}")
    private String tokenizerPath;

    public EmbeddingConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * Multilingual Bi-Encoder embedding model for Stage 1 candidate retrieval.
     * Uses paraphrase-multilingual-MiniLM-L12-v2 for Korean + English support.
     * Runs locally on CPU using ONNX Runtime.
     */
    @Bean
    public EmbeddingModel embeddingModel() throws IOException {
        log.info("Initializing multilingual embedding model (paraphrase-multilingual-MiniLM-L12-v2)");
        
        // Extract resources to temp files if they're classpath resources
        Path onnxModelPath = extractResourceToTempFile(modelPath, "model", ".onnx");
        Path tokenizerFilePath = extractResourceToTempFile(tokenizerPath, "tokenizer", ".json");
        
        log.info("Loading ONNX model from: {}", onnxModelPath);
        log.info("Loading tokenizer from: {}", tokenizerFilePath);
        
        // Use MEAN pooling for sentence-transformers compatibility
        OnnxEmbeddingModel model = new OnnxEmbeddingModel(
            onnxModelPath,
            tokenizerFilePath,
            PoolingMode.MEAN
        );
        
        log.info("Multilingual embedding model loaded successfully (384 dimensions)");
        return model;
    }

    /**
     * Extract a classpath resource to a temporary file.
     * ONNX Runtime needs file paths, not classpath resources.
     */
    private Path extractResourceToTempFile(String resourcePath, String prefix, String suffix) throws IOException {
        Resource resource = resourceLoader.getResource(resourcePath);
        
        if (!resource.exists()) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        
        // If it's a file resource, return the path directly
        try {
            return resource.getFile().toPath();
        } catch (IOException e) {
            // It's a classpath resource, extract to temp file
            Path tempFile = Files.createTempFile(prefix, suffix);
            tempFile.toFile().deleteOnExit();
            
            log.debug("Extracting {} to {}", resourcePath, tempFile);
            Files.copy(resource.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            
            return tempFile;
        }
    }

    /**
     * In-memory embedding store for Bible verse vectors.
     * Stores pre-computed embeddings for fast similarity search.
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("Initializing in-memory embedding store");
        return new InMemoryEmbeddingStore<>();
    }
}
