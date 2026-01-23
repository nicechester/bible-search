package io.github.nicechester.biblesearch.service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Service for persisting the embedding store to Google Cloud Storage.
 * 
 * This dramatically reduces cold start time by:
 * 1. Saving pre-computed embeddings to GCS after first generation
 * 2. Loading from GCS on subsequent startups (seconds instead of minutes)
 * 
 * The InMemoryEmbeddingStore is serialized to JSON format.
 */
@Slf4j
@Service
public class EmbeddingStoreService {

    private final Storage storage;
    private final String bucketName;
    private final String blobName;
    private final boolean enabled;

    public EmbeddingStoreService(
            @Value("${bible.embedding.gcs.enabled:false}") boolean enabled,
            @Value("${bible.embedding.gcs.bucket:}") String bucketName,
            @Value("${bible.embedding.gcs.blob-name:embeddings/bible-embeddings.json}") String blobName) {
        
        this.enabled = enabled;
        this.bucketName = bucketName;
        this.blobName = blobName;
        
        if (enabled && !bucketName.isBlank()) {
            this.storage = StorageOptions.getDefaultInstance().getService();
            log.info("GCS embedding persistence enabled: gs://{}/{}", bucketName, blobName);
        } else {
            this.storage = null;
            log.info("GCS embedding persistence disabled (set bible.embedding.gcs.enabled=true to enable)");
        }
    }

    /**
     * Check if embeddings exist in GCS.
     */
    public boolean existsInGcs() {
        if (!enabled || storage == null) {
            return false;
        }
        
        try {
            Blob blob = storage.get(BlobId.of(bucketName, blobName));
            boolean exists = blob != null && blob.exists();
            log.debug("Embeddings in GCS: {}", exists ? "found" : "not found");
            return exists;
        } catch (Exception e) {
            log.warn("Failed to check GCS for embeddings: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Load embedding store from GCS.
     * Returns null if not found or disabled.
     */
    public EmbeddingStore<TextSegment> loadFromGcs() {
        if (!enabled || storage == null) {
            return null;
        }

        try {
            log.info("Loading embeddings from GCS: gs://{}/{}", bucketName, blobName);
            long startTime = System.currentTimeMillis();

            Blob blob = storage.get(BlobId.of(bucketName, blobName));
            if (blob == null || !blob.exists()) {
                log.info("No embeddings found in GCS");
                return null;
            }

            byte[] content = blob.getContent();
            String json = new String(content, StandardCharsets.UTF_8);
            
            InMemoryEmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromJson(json);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Loaded embeddings from GCS in {}ms ({} bytes)", duration, content.length);
            
            return store;
        } catch (Exception e) {
            log.error("Failed to load embeddings from GCS: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Save embedding store to GCS.
     */
    public void saveToGcs(EmbeddingStore<TextSegment> embeddingStore) {
        if (!enabled || storage == null) {
            log.debug("GCS persistence disabled, skipping save");
            return;
        }

        if (!(embeddingStore instanceof InMemoryEmbeddingStore)) {
            log.warn("Cannot save to GCS: embedding store is not InMemoryEmbeddingStore");
            return;
        }

        try {
            log.info("Saving embeddings to GCS: gs://{}/{}", bucketName, blobName);
            long startTime = System.currentTimeMillis();

            InMemoryEmbeddingStore<TextSegment> store = (InMemoryEmbeddingStore<TextSegment>) embeddingStore;
            String json = store.serializeToJson();
            byte[] content = json.getBytes(StandardCharsets.UTF_8);

            BlobId blobId = BlobId.of(bucketName, blobName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("application/json")
                    .build();

            storage.create(blobInfo, content);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Saved embeddings to GCS in {}ms ({} bytes)", duration, content.length);
        } catch (Exception e) {
            log.error("Failed to save embeddings to GCS: {}", e.getMessage(), e);
        }
    }

    /**
     * Delete embeddings from GCS (useful for forcing regeneration).
     */
    public void deleteFromGcs() {
        if (!enabled || storage == null) {
            return;
        }

        try {
            storage.delete(BlobId.of(bucketName, blobName));
            log.info("Deleted embeddings from GCS");
        } catch (Exception e) {
            log.warn("Failed to delete embeddings from GCS: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getGcsPath() {
        return enabled ? "gs://" + bucketName + "/" + blobName : "disabled";
    }
}
