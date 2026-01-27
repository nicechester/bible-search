package io.github.nicechester.biblesearch.config;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import io.github.nicechester.biblesearch.store.SqliteEmbeddingStore;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * 
 * Embedding Store Priority:
 * 1. SQLite (if enabled) - fastest cold start, pre-built database in Docker image
 * 2. GCS (if enabled) - network load, still fast
 * 3. In-memory with generation - slowest, for development only
 */
@Slf4j
@Configuration
public class EmbeddingConfig {

    private final ResourceLoader resourceLoader;

    @Value("${bible.embedding.model-path:classpath:models/multilingual-minilm/model.onnx}")
    private String modelPath;

    @Value("${bible.embedding.tokenizer-path:classpath:models/multilingual-minilm/tokenizer.json}")
    private String tokenizerPath;

    // SQLite configuration (preferred for production)
    @Value("${bible.embedding.sqlite.enabled:false}")
    private boolean sqliteEnabled;

    @Value("${bible.embedding.sqlite.path:classpath:embeddings/bible-embeddings.db}")
    private String sqlitePath;

    // GCS configuration (fallback)
    @Value("${bible.embedding.gcs.enabled:false}")
    private boolean gcsEnabled;

    @Value("${bible.embedding.gcs.bucket:}")
    private String gcsBucket;

    @Value("${bible.embedding.gcs.blob-name:embeddings/bible-embeddings.json}")
    private String gcsBlobName;

    // Track how store was loaded (used by stats)
    @Getter
    private String loadedFrom = "generated";

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
     * Embedding store for Bible verse vectors.
     * Priority: SQLite (fastest) → GCS (network) → In-memory (slowest)
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // Priority 1: Try SQLite (fastest - local file, no network)
        if (sqliteEnabled) {
            EmbeddingStore<TextSegment> sqliteStore = tryLoadFromSqlite();
            if (sqliteStore != null) {
                loadedFrom = "sqlite";
                return sqliteStore;
            }
        }
        
        // Priority 2: Try GCS (network, but pre-computed)
        if (gcsEnabled && gcsBucket != null && !gcsBucket.isBlank()) {
            EmbeddingStore<TextSegment> gcsStore = tryLoadFromGcs();
            if (gcsStore != null) {
                loadedFrom = "gcs";
                return gcsStore;
            }
        }
        
        // Priority 3: Empty in-memory store (will generate embeddings)
        log.info("Initializing empty in-memory embedding store (will generate embeddings)");
        loadedFrom = "generated";
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * Try to load embedding store from SQLite database.
     */
    private EmbeddingStore<TextSegment> tryLoadFromSqlite() {
        try {
            log.info("Checking for SQLite embedding database: {}", sqlitePath);
            
            String resolvedPath = sqlitePath;
            
            // Handle classpath resources
            if (sqlitePath.startsWith("classpath:")) {
                String resourcePath = sqlitePath.substring("classpath:".length());
                Resource resource = resourceLoader.getResource(sqlitePath);
                
                if (!resource.exists()) {
                    log.info("SQLite database not found in classpath: {}", resourcePath);
                    return null;
                }
                
                // Extract to temp file (SQLite needs file access)
                try (InputStream is = resource.getInputStream()) {
                    Path tempFile = Files.createTempFile("bible-embeddings", ".db");
                    Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    resolvedPath = tempFile.toString();
                    log.info("Extracted SQLite database from classpath to: {}", resolvedPath);
                }
            } else {
                // Check if file exists
                if (!Files.exists(Path.of(resolvedPath))) {
                    log.info("SQLite database file not found: {}", resolvedPath);
                    return null;
                }
            }
            
            long startTime = System.currentTimeMillis();
            
            SqliteEmbeddingStore store = new SqliteEmbeddingStore(resolvedPath);
            
            if (!store.hasEmbeddings()) {
                log.info("SQLite database is empty");
                store.close();
                return null;
            }
            
            // Load embeddings into memory cache for fast searching
            store.loadCache();
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Loaded {} embeddings from SQLite in {}ms", store.size(), duration);
            
            return store;
            
        } catch (Exception e) {
            log.warn("Failed to load embeddings from SQLite: {} - trying next option", e.getMessage());
            return null;
        }
    }

    /**
     * Try to load embedding store from GCS.
     */
    private EmbeddingStore<TextSegment> tryLoadFromGcs() {
        try {
            log.info("Checking GCS for pre-computed embeddings: gs://{}/{}", gcsBucket, gcsBlobName);
            
            Storage storage = StorageOptions.getDefaultInstance().getService();
            Blob blob = storage.get(BlobId.of(gcsBucket, gcsBlobName));
            
            if (blob == null || !blob.exists()) {
                log.info("No embeddings found in GCS");
                return null;
            }
            
            log.info("Loading embeddings from GCS...");
            long startTime = System.currentTimeMillis();
            
            byte[] content = blob.getContent();
            String json = new String(content, StandardCharsets.UTF_8);
            
            InMemoryEmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromJson(json);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Loaded embeddings from GCS in {}ms ({} MB)", 
                    duration, content.length / 1024 / 1024);
            
            return store;
        } catch (Exception e) {
            log.warn("Failed to load embeddings from GCS: {} - will generate instead", e.getMessage());
            return null;
        }
    }

    /**
     * @deprecated Use {@link #getLoadedFrom()} instead
     */
    @Deprecated
    public boolean isLoadedFromGcs() {
        return "gcs".equals(loadedFrom) || "sqlite".equals(loadedFrom);
    }
}
