package io.github.nicechester.biblesearch.controller;

import io.github.nicechester.biblesearch.model.SearchRequest;
import io.github.nicechester.biblesearch.model.SearchResponse;
import io.github.nicechester.biblesearch.model.VerseResult;
import io.github.nicechester.biblesearch.service.BibleDataService;
import io.github.nicechester.biblesearch.service.BibleSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Bible semantic search.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SearchController {

    private final BibleSearchService searchService;
    private final BibleDataService bibleDataService;

    /**
     * Perform semantic search on Bible verses.
     * 
     * POST /api/search
     * {
     *   "query": "love your neighbor",
     *   "maxResults": 5,
     *   "minScore": 0.3,
     *   "version": "ASV"  // optional: "ASV", "KRV", or null for all
     * }
     */
    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest request) {
        log.info("Search request: query='{}', maxResults={}, minScore={}, version={}",
            request.query(), request.maxResults(), request.minScore(), request.version());

        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest()
                .body(SearchResponse.error(null, "Query cannot be empty"));
        }

        SearchResponse response = searchService.search(
            request.query(),
            request.maxResults(),
            request.minScore(),
            request.version()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Simple GET endpoint for quick searches.
     * 
     * GET /api/search?q=love&max=5&version=ASV
     */
    @GetMapping("/search")
    public ResponseEntity<SearchResponse> searchGet(
            @RequestParam("q") String query,
            @RequestParam(value = "max", required = false, defaultValue = "5") Integer maxResults,
            @RequestParam(value = "min", required = false, defaultValue = "0.3") Double minScore,
            @RequestParam(value = "version", required = false) String version) {

        log.info("GET Search: query='{}', max={}, min={}, version={}", query, maxResults, minScore, version);

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest()
                .body(SearchResponse.error(null, "Query parameter 'q' is required"));
        }

        SearchResponse response = searchService.search(query, maxResults, minScore, version);
        return ResponseEntity.ok(response);
    }

    /**
     * Get search service statistics and configuration.
     * 
     * GET /api/search/stats
     */
    @GetMapping("/search/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(searchService.getStats());
    }

    /**
     * Health check endpoint.
     * 
     * GET /api/search/health
     */
    @GetMapping("/search/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "service", "bible-search"
        ));
    }

    // ==================== Bible Reading Endpoints ====================

    /**
     * Get all verses in a specific chapter for Bible reading.
     * 
     * GET /api/bible/{bookShort}/{chapter}?version=KRV
     */
    @GetMapping("/bible/{bookShort}/{chapter}")
    public ResponseEntity<Map<String, Object>> getChapter(
            @PathVariable String bookShort,
            @PathVariable int chapter,
            @RequestParam(value = "version", required = false) String version) {
        
        log.info("Reading chapter: {} {} (version={})", bookShort, chapter, version);
        
        List<BibleDataService.VerseData> verses = bibleDataService.getChapterVerses(bookShort, chapter, version);
        
        if (verses.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        BibleDataService.VerseData firstVerse = verses.get(0);
        Map<String, Object> bookInfo = bibleDataService.getBookInfo(bookShort, version);
        
        List<VerseResult> verseResults = verses.stream()
            .map(v -> bibleDataService.toVerseResult(v, 1.0))
            .toList();
        
        Map<String, Object> response = Map.of(
            "bookName", firstVerse.getBookName(),
            "bookShort", firstVerse.getBookShort(),
            "chapter", chapter,
            "version", firstVerse.getVersion(),
            "totalChapters", bookInfo.getOrDefault("totalChapters", 0),
            "verses", verseResults
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get book information (metadata and chapter count).
     * 
     * GET /api/bible/{bookShort}?version=KRV
     */
    @GetMapping("/bible/{bookShort}")
    public ResponseEntity<Map<String, Object>> getBookInfo(
            @PathVariable String bookShort,
            @RequestParam(value = "version", required = false) String version) {
        
        Map<String, Object> info = bibleDataService.getBookInfo(bookShort, version);
        
        if (info.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(info);
    }
}
