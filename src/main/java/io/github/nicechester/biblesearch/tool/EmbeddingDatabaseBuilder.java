package io.github.nicechester.biblesearch.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import io.github.nicechester.biblesearch.store.SqliteEmbeddingStore;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone tool to pre-build the SQLite embedding database.
 * 
 * <p>Usage:
 * <pre>
 * mvn exec:java -Dexec.mainClass="io.github.nicechester.biblesearch.tool.EmbeddingDatabaseBuilder" \
 *     -Dexec.args="--output target/classes/embeddings/bible-embeddings.db"
 * </pre>
 * 
 * <p>Or via the build script:
 * <pre>
 * ./build-embeddings.sh
 * </pre>
 */
public class EmbeddingDatabaseBuilder {

    private static final String DEFAULT_OUTPUT = "target/classes/embeddings/bible-embeddings.db";
    private static final String MODEL_PATH = "models/multilingual-minilm/model.onnx";
    private static final String TOKENIZER_PATH = "models/multilingual-minilm/tokenizer.json";
    private static final String[] BIBLE_FILES = {
        "bible/bible_krv.json",
        "bible/bible_asv.json"
    };

    public static void main(String[] args) throws Exception {
        String outputPath = DEFAULT_OUTPUT;
        
        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputPath = args[++i];
            } else if ("--help".equals(args[i])) {
                printHelp();
                return;
            }
        }
        
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║           Bible Embedding Database Builder                 ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        new EmbeddingDatabaseBuilder().build(outputPath);
    }

    private static void printHelp() {
        System.out.println("Usage: EmbeddingDatabaseBuilder [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --output <path>  Output path for SQLite database");
        System.out.println("                   Default: " + DEFAULT_OUTPUT);
        System.out.println("  --help           Show this help message");
    }

    public void build(String outputPath) throws Exception {
        long startTime = System.currentTimeMillis();
        
        // Create output directory if needed
        Path outputFile = Path.of(outputPath);
        Files.createDirectories(outputFile.getParent());
        
        // Delete existing database
        if (Files.exists(outputFile)) {
            Files.delete(outputFile);
            System.out.println("Deleted existing database: " + outputPath);
        }
        
        // Initialize embedding model
        System.out.println("Loading embedding model...");
        EmbeddingModel embeddingModel = loadEmbeddingModel();
        int dimensions = embeddingModel.dimension();
        System.out.println("✓ Model loaded (" + dimensions + " dimensions)");
        
        // Initialize SQLite store
        System.out.println("Creating SQLite database: " + outputPath);
        SqliteEmbeddingStore store = new SqliteEmbeddingStore(outputPath);
        System.out.println("✓ Database created");
        
        // Load Bible data
        System.out.println("Loading Bible data...");
        List<VerseEntry> verses = loadBibleData();
        System.out.println("✓ Loaded " + verses.size() + " verses");
        
        // Generate embeddings in batches
        System.out.println();
        System.out.println("Generating embeddings...");
        int batchSize = 100;
        int totalBatches = (verses.size() + batchSize - 1) / batchSize;
        AtomicInteger processed = new AtomicInteger(0);
        
        for (int i = 0; i < verses.size(); i += batchSize) {
            int end = Math.min(i + batchSize, verses.size());
            List<VerseEntry> batch = verses.subList(i, end);
            
            // Create text segments
            List<TextSegment> segments = batch.stream()
                .map(v -> TextSegment.from(v.embeddingText))
                .toList();
            
            // Generate embeddings
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            
            // Store in SQLite
            store.addAll(embeddings, segments);
            
            int done = processed.addAndGet(batch.size());
            int currentBatch = (i / batchSize) + 1;
            
            // Progress bar
            int progress = (done * 50) / verses.size();
            String bar = "█".repeat(progress) + "░".repeat(50 - progress);
            System.out.printf("\r[%s] %d/%d (%d%%) - Batch %d/%d", 
                bar, done, verses.size(), (done * 100) / verses.size(), 
                currentBatch, totalBatches);
        }
        
        System.out.println();
        System.out.println();
        
        // Optimize database
        System.out.println("Optimizing database...");
        store.optimize();
        store.close();
        System.out.println("✓ Database optimized");
        
        // Print stats
        long elapsed = System.currentTimeMillis() - startTime;
        long fileSize = Files.size(outputFile);
        
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════");
        System.out.println("Build complete!");
        System.out.println("────────────────────────────────────────────────────────────");
        System.out.printf("  Output:     %s%n", outputPath);
        System.out.printf("  Verses:     %,d%n", verses.size());
        System.out.printf("  File size:  %,.1f MB%n", fileSize / (1024.0 * 1024.0));
        System.out.printf("  Time:       %d min %d sec%n", elapsed / 60000, (elapsed % 60000) / 1000);
        System.out.println("════════════════════════════════════════════════════════════");
    }

    private EmbeddingModel loadEmbeddingModel() throws Exception {
        // Try classpath first, then file system
        Path modelPath = extractResource(MODEL_PATH);
        Path tokenizerPath = extractResource(TOKENIZER_PATH);
        
        return new OnnxEmbeddingModel(
            modelPath.toString(),
            tokenizerPath.toString(),
            PoolingMode.MEAN
        );
    }

    private Path extractResource(String resourcePath) throws Exception {
        // Check if file exists in file system (target/classes)
        Path filePath = Path.of("target/classes", resourcePath);
        if (Files.exists(filePath)) {
            return filePath;
        }
        
        // Try classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("Resource not found: " + resourcePath);
            }
            
            // Extract to temp file
            Path tempFile = Files.createTempFile("embed-", "-" + Path.of(resourcePath).getFileName());
            Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tempFile.toFile().deleteOnExit();
            return tempFile;
        }
    }

    private List<VerseEntry> loadBibleData() throws Exception {
        List<VerseEntry> allVerses = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        
        for (String bibleFile : BIBLE_FILES) {
            Path filePath = Path.of("target/classes", bibleFile);
            InputStream is;
            
            if (Files.exists(filePath)) {
                is = Files.newInputStream(filePath);
            } else {
                is = getClass().getClassLoader().getResourceAsStream(bibleFile);
            }
            
            if (is == null) {
                System.err.println("Warning: Bible file not found: " + bibleFile);
                continue;
            }
            
            try (is) {
                // Parse the Bible JSON structure: { version, language, books: [...] }
                Map<String, Object> bibleData = mapper.readValue(is, 
                    new TypeReference<Map<String, Object>>() {});
                
                String version = (String) bibleData.get("version");
                
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> books = (List<Map<String, Object>>) bibleData.get("books");
                
                for (Map<String, Object> book : books) {
                    String bookName = (String) book.get("bookName");
                    String bookShort = (String) book.get("bookShort");
                    
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> chapters = (List<Map<String, Object>>) book.get("chapters");
                    
                    for (Map<String, Object> chapter : chapters) {
                        int chapterNum = (Integer) chapter.get("chapter");
                        
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> verses = (List<Map<String, Object>>) chapter.get("verses");
                        
                        for (Map<String, Object> verseData : verses) {
                            int verseNum = (Integer) verseData.get("verse");
                            String text = (String) verseData.get("text");
                            String title = (String) verseData.get("title");
                            
                            String reference = String.format("%s %d:%d", bookName, chapterNum, verseNum);
                            
                            // Match the format used by BibleDataService.toEmbeddingText()
                            StringBuilder embeddingText = new StringBuilder();
                            embeddingText.append("[").append(version).append("] ");
                            embeddingText.append(bookName).append(" ").append(chapterNum).append(":").append(verseNum);
                            if (title != null && !title.isEmpty()) {
                                embeddingText.append(" <").append(title).append(">");
                            }
                            embeddingText.append(" ").append(text);
                            
                            allVerses.add(new VerseEntry(
                                version + ":" + bookShort + ":" + chapterNum + ":" + verseNum,
                                reference,
                                embeddingText.toString(),
                                version
                            ));
                        }
                    }
                }
                
                System.out.println("  - Loaded " + version + " Bible (" + books.size() + " books)");
            }
        }
        
        return allVerses;
    }

    private record VerseEntry(String id, String reference, String embeddingText, String version) {}
}
