package io.github.nicechester.biblesearch.store;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * SQLite-backed embedding store for fast cold starts.
 * 
 * <p>This store persists embeddings to a SQLite database file, which can be:
 * <ul>
 *   <li>Pre-built during the build process</li>
 *   <li>Included in the Docker image</li>
 *   <li>Loaded instantly on startup (no network calls)</li>
 * </ul>
 * 
 * <p>Benefits over GCS-based loading:
 * <ul>
 *   <li>No network latency on cold start</li>
 *   <li>No JSON parsing overhead</li>
 *   <li>Memory-efficient (SQLite can use mmap)</li>
 *   <li>Predictable startup time (~100ms vs 10-30s)</li>
 * </ul>
 */
@Slf4j
public class SqliteEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS embeddings (
            id TEXT PRIMARY KEY,
            text TEXT NOT NULL,
            metadata TEXT,
            embedding BLOB NOT NULL
        )
        """;

    private static final String CREATE_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS idx_embeddings_id ON embeddings(id)
        """;

    private static final String INSERT_SQL = """
        INSERT OR REPLACE INTO embeddings (id, text, metadata, embedding) VALUES (?, ?, ?, ?)
        """;

    private static final String SELECT_ALL_SQL = """
        SELECT id, text, metadata, embedding FROM embeddings
        """;

    private static final String COUNT_SQL = """
        SELECT COUNT(*) FROM embeddings
        """;

    private final String dbPath;
    private Connection connection;
    
    // In-memory cache for fast searching (loaded once on startup)
    private final List<StoredEmbedding> embeddingCache = new ArrayList<>();
    private boolean cacheLoaded = false;

    /**
     * Creates a new SQLite embedding store.
     * 
     * @param dbPath Path to the SQLite database file
     */
    public SqliteEmbeddingStore(String dbPath) {
        this.dbPath = dbPath;
        initializeDatabase();
    }

    /**
     * Creates a store from a classpath resource or file path.
     * 
     * @param path Path to database (can be classpath: prefix or file path)
     * @return The embedding store
     */
    public static SqliteEmbeddingStore fromPath(String path) {
        String resolvedPath = path;
        
        if (path.startsWith("classpath:")) {
            // Extract from classpath to temp file (SQLite needs file access)
            String resourcePath = path.substring("classpath:".length());
            try {
                var resource = SqliteEmbeddingStore.class.getResourceAsStream("/" + resourcePath);
                if (resource != null) {
                    Path tempFile = Files.createTempFile("embeddings", ".db");
                    Files.copy(resource, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    resolvedPath = tempFile.toString();
                    log.info("Extracted embedding database from classpath to: {}", resolvedPath);
                } else {
                    log.warn("Classpath resource not found: {}, creating new database", resourcePath);
                    resolvedPath = resourcePath;
                }
            } catch (Exception e) {
                log.error("Failed to extract classpath resource: {}", e.getMessage());
                throw new RuntimeException("Failed to load embedding database from classpath", e);
            }
        }
        
        return new SqliteEmbeddingStore(resolvedPath);
    }

    private void initializeDatabase() {
        try {
            // Connect with busy timeout to handle concurrent access
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath + "?busy_timeout=30000");
            
            // Execute pragmas with proper statement management
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA synchronous=NORMAL");
            }
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA busy_timeout=30000");
            }
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
            }
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(CREATE_INDEX_SQL);
            }
            
            log.info("SQLite embedding store initialized: {}", dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite database: " + dbPath, e);
        }
    }

    /**
     * Loads all embeddings into memory cache for fast searching.
     * Call this once after opening an existing database.
     */
    public void loadCache() {
        if (cacheLoaded) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        embeddingCache.clear();
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_ALL_SQL)) {
            
            while (rs.next()) {
                String id = rs.getString("id");
                String text = rs.getString("text");
                String metadata = rs.getString("metadata");
                byte[] embeddingBytes = rs.getBytes("embedding");
                
                float[] vector = bytesToFloats(embeddingBytes);
                Embedding embedding = Embedding.from(vector);
                TextSegment segment = TextSegment.from(text);
                
                embeddingCache.add(new StoredEmbedding(id, segment, embedding));
            }
            
            cacheLoaded = true;
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Loaded {} embeddings into cache in {}ms", embeddingCache.size(), elapsed);
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load embeddings from SQLite", e);
        }
    }

    @Override
    public String add(Embedding embedding) {
        String id = UUID.randomUUID().toString();
        add(id, embedding);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        addInternal(id, embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = UUID.randomUUID().toString();
        addInternal(id, embedding, textSegment);
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = new ArrayList<>();
        for (Embedding embedding : embeddings) {
            ids.add(add(embedding));
        }
        return ids;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        if (embeddings.size() != textSegments.size()) {
            throw new IllegalArgumentException("Embeddings and text segments must have the same size");
        }
        
        List<String> ids = new ArrayList<>();
        try {
            connection.setAutoCommit(false);
            
            for (int i = 0; i < embeddings.size(); i++) {
                String id = UUID.randomUUID().toString();
                addInternal(id, embeddings.get(i), textSegments.get(i));
                ids.add(id);
            }
            
            connection.commit();
            connection.setAutoCommit(true);
            
            log.debug("Added {} embeddings to SQLite store", ids.size());
            
        } catch (SQLException e) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                log.error("Failed to rollback transaction", ex);
            }
            throw new RuntimeException("Failed to add embeddings", e);
        }
        
        return ids;
    }

    private void addInternal(String id, Embedding embedding, TextSegment textSegment) {
        try (PreparedStatement pstmt = connection.prepareStatement(INSERT_SQL)) {
            pstmt.setString(1, id);
            pstmt.setString(2, textSegment != null ? textSegment.text() : "");
            pstmt.setString(3, textSegment != null && textSegment.metadata() != null 
                ? textSegment.metadata().toMap().toString() : null);
            pstmt.setBytes(4, floatsToBytes(embedding.vector()));
            pstmt.executeUpdate();
            
            // Update cache
            if (cacheLoaded) {
                embeddingCache.add(new StoredEmbedding(id, textSegment, embedding));
            }
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add embedding: " + id, e);
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        // Ensure cache is loaded
        if (!cacheLoaded) {
            loadCache();
        }
        
        Embedding queryEmbedding = request.queryEmbedding();
        int maxResults = request.maxResults();
        double minScore = request.minScore();
        
        // Calculate cosine similarities
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        
        for (StoredEmbedding stored : embeddingCache) {
            double score = cosineSimilarity(queryEmbedding.vector(), stored.embedding.vector());
            
            if (score >= minScore) {
                matches.add(new EmbeddingMatch<>(score, stored.id, stored.embedding, stored.textSegment));
            }
        }
        
        // Sort by score descending and limit
        matches.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (matches.size() > maxResults) {
            matches = matches.subList(0, maxResults);
        }
        
        return new EmbeddingSearchResult<>(matches);
    }

    /**
     * Returns the number of embeddings in the store.
     */
    public int size() {
        if (cacheLoaded) {
            return embeddingCache.size();
        }
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(COUNT_SQL)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to count embeddings", e);
        }
        return 0;
    }

    /**
     * Checks if the database file exists and has embeddings.
     */
    public boolean hasEmbeddings() {
        return size() > 0;
    }

    /**
     * Optimizes the database after bulk inserts.
     */
    public void optimize() {
        try {
            connection.createStatement().execute("PRAGMA optimize");
            connection.createStatement().execute("VACUUM");
            log.info("SQLite database optimized");
        } catch (SQLException e) {
            log.warn("Failed to optimize database: {}", e.getMessage());
        }
    }

    /**
     * Closes the database connection.
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("SQLite connection closed");
            }
        } catch (SQLException e) {
            log.error("Failed to close SQLite connection", e);
        }
    }

    // --- Utility methods ---

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have the same length");
        }
        
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static byte[] floatsToBytes(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    private static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    // --- Inner class ---

    private record StoredEmbedding(String id, TextSegment textSegment, Embedding embedding) {}
}
