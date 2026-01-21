package io.github.nicechester.biblesearch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nicechester.biblesearch.model.VerseResult;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Service for loading and managing Bible data from JSON files.
 * Supports both Korean (KRV) and English (ASV) Bible versions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BibleDataService {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    @Value("${bible.data.json-path}")
    private String krvJsonPath;

    @Value("${bible.data.asv-json-path}")
    private String asvJsonPath;

    // In-memory storage for quick verse lookup by reference
    private final Map<String, VerseData> versesByReference = new HashMap<>();
    private final List<VerseData> allVerses = new ArrayList<>();

    @Data
    public static class VerseData {
        private String reference;
        private String bookName;
        private String bookShort;
        private Integer testament;
        private Integer bookNumber;
        private Integer chapter;
        private Integer verse;
        private String title;
        private String text;
        private String version;

        /**
         * Creates a formatted text for embedding.
         * Format: "[VERSION] BookName Chapter:Verse <Title> Text"
         */
        public String toEmbeddingText() {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(version).append("] ");
            sb.append(bookName).append(" ").append(chapter).append(":").append(verse);
            if (title != null && !title.isEmpty()) {
                sb.append(" <").append(title).append(">");
            }
            sb.append(" ").append(text);
            return sb.toString();
        }

        /**
         * Creates a unique key for this verse.
         */
        public String getKey() {
            return version + ":" + bookShort + ":" + chapter + ":" + verse;
        }
    }

    @PostConstruct
    public void loadBibleData() {
        log.info("Loading Bible data...");
        
        // Load Korean Bible (KRV)
        try {
            loadBibleJson(krvJsonPath, "KRV");
        } catch (Exception e) {
            log.error("Failed to load KRV Bible: {}", e.getMessage());
        }

        // Load English Bible (ASV)
        try {
            loadBibleJson(asvJsonPath, "ASV");
        } catch (Exception e) {
            log.warn("Failed to load ASV Bible: {}", e.getMessage());
        }

        log.info("Loaded {} total verses from Bible data", allVerses.size());
    }

    private void loadBibleJson(String jsonPath, String defaultVersion) throws IOException {
        Resource resource = resourceLoader.getResource(jsonPath);
        if (!resource.exists()) {
            log.warn("Bible JSON file not found: {}", jsonPath);
            return;
        }

        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);

            String version = root.has("version") ? root.get("version").asText() : defaultVersion;
            log.info("Loading {} Bible from: {}", version, jsonPath);

            JsonNode booksNode = root.get("books");
            if (booksNode == null || !booksNode.isArray()) {
                log.warn("No books found in Bible JSON: {}", jsonPath);
                return;
            }

            int bookCount = 0;
            int verseCount = 0;

            for (JsonNode bookNode : booksNode) {
                String bookName = bookNode.get("bookName").asText();
                String bookShort = bookNode.get("bookShort").asText();
                int testament = bookNode.has("testament") ? bookNode.get("testament").asInt() : 0;
                int bookNumber = bookNode.has("bookNumber") ? bookNode.get("bookNumber").asInt() : 0;

                JsonNode chaptersNode = bookNode.get("chapters");
                if (chaptersNode == null || !chaptersNode.isArray()) continue;

                for (JsonNode chapterNode : chaptersNode) {
                    int chapterNum = chapterNode.get("chapter").asInt();

                    JsonNode versesNode = chapterNode.get("verses");
                    if (versesNode == null || !versesNode.isArray()) continue;

                    for (JsonNode verseNode : versesNode) {
                        int verseNum = verseNode.get("verse").asInt();
                        String text = verseNode.has("text") ? verseNode.get("text").asText() : "";
                        String title = verseNode.has("title") && !verseNode.get("title").isNull()
                            ? verseNode.get("title").asText() : null;

                        VerseData verse = new VerseData();
                        verse.setVersion(version);
                        verse.setBookName(bookName);
                        verse.setBookShort(bookShort);
                        verse.setTestament(testament);
                        verse.setBookNumber(bookNumber);
                        verse.setChapter(chapterNum);
                        verse.setVerse(verseNum);
                        verse.setTitle(title);
                        verse.setText(text);
                        verse.setReference(bookName + " " + chapterNum + ":" + verseNum);

                        allVerses.add(verse);
                        versesByReference.put(verse.getKey(), verse);
                        verseCount++;
                    }
                }
                bookCount++;
            }

            log.info("Loaded {} books, {} verses from {} Bible", bookCount, verseCount, version);
        }
    }

    /**
     * Get all verses for embedding indexing.
     */
    public List<VerseData> getAllVerses() {
        return Collections.unmodifiableList(allVerses);
    }

    /**
     * Get a verse by its unique key (version:book:chapter:verse).
     */
    public Optional<VerseData> getVerseByKey(String key) {
        return Optional.ofNullable(versesByReference.get(key));
    }

    /**
     * Search verses by keyword (exact match).
     */
    public List<VerseData> searchByKeyword(String keyword) {
        return allVerses.stream()
            .filter(v -> v.getText() != null && v.getText().contains(keyword))
            .toList();
    }

    /**
     * Get all verses in a specific chapter.
     */
    public List<VerseData> getChapterVerses(String bookShort, int chapter, String version) {
        return allVerses.stream()
            .filter(v -> v.getBookShort().equalsIgnoreCase(bookShort))
            .filter(v -> v.getChapter() == chapter)
            .filter(v -> version == null || version.isEmpty() || 
                        v.getVersion().equalsIgnoreCase(version) ||
                        (version.equalsIgnoreCase("KRV") && v.getVersion().equals("개역개정")))
            .sorted((a, b) -> Integer.compare(a.getVerse(), b.getVerse()))
            .toList();
    }

    /**
     * Get book information (chapters list) by book short name.
     */
    public Map<String, Object> getBookInfo(String bookShort, String version) {
        Map<String, Object> result = new HashMap<>();
        
        List<VerseData> bookVerses = allVerses.stream()
            .filter(v -> v.getBookShort().equalsIgnoreCase(bookShort))
            .filter(v -> version == null || version.isEmpty() || 
                        v.getVersion().equalsIgnoreCase(version) ||
                        (version.equalsIgnoreCase("KRV") && v.getVersion().equals("개역개정")))
            .toList();
        
        if (bookVerses.isEmpty()) {
            return result;
        }
        
        VerseData sample = bookVerses.get(0);
        result.put("bookName", sample.getBookName());
        result.put("bookShort", sample.getBookShort());
        result.put("version", sample.getVersion());
        
        int maxChapter = bookVerses.stream()
            .mapToInt(VerseData::getChapter)
            .max()
            .orElse(0);
        result.put("totalChapters", maxChapter);
        
        return result;
    }

    /**
     * Get verse count statistics.
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("totalVerses", allVerses.size());
        
        long krvCount = allVerses.stream().filter(v -> "KRV".equals(v.getVersion())).count();
        long asvCount = allVerses.stream().filter(v -> "ASV".equals(v.getVersion())).count();
        
        stats.put("krvVerses", (int) krvCount);
        stats.put("asvVerses", (int) asvCount);
        
        return stats;
    }

    /**
     * Convert VerseData to VerseResult for API responses.
     */
    public VerseResult toVerseResult(VerseData verse, double score) {
        return VerseResult.builder()
            .reference(verse.getReference())
            .bookName(verse.getBookName())
            .bookShort(verse.getBookShort())
            .chapter(verse.getChapter())
            .verse(verse.getVerse())
            .title(verse.getTitle())
            .text(verse.getText())
            .version(verse.getVersion())
            .score(score)
            .build();
    }
}
