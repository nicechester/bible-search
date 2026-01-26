package io.github.nicechester.biblesearch.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.nicechester.biblesearch.model.ContextResult;
import io.github.nicechester.biblesearch.model.ContextResult.ContextType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Context classifier that uses embedding similarity to detect scope constraints in queries.
 * 
 * <p>This service extracts:
 * <ul>
 *   <li>Testament constraints: "신약에서", "구약에서", "in NT"</li>
 *   <li>Book groups: "사복음서", "모세오경", "바울서신"</li>
 *   <li>Single books: "로마서에서", "창세기에서"</li>
 *   <li>Multiple books: "이사야, 예레미야에서"</li>
 * </ul>
 * 
 * <p>Uses embedding similarity to detect if a query has a context constraint,
 * then uses regex patterns to extract the specific scope.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextClassifierService {

    private final EmbeddingModel embeddingModel;

    // Pre-computed embeddings for context detection
    private final List<Embedding> contextPrototypeEmbeddings = new ArrayList<>();
    private final List<Embedding> noContextPrototypeEmbeddings = new ArrayList<>();

    // Prototype phrases that HAVE context constraints
    private static final List<String> CONTEXT_PROTOTYPES = List.of(
        // Testament constraints - Korean
        "신약에서 나오는 구절",
        "구약에서 언급된 말씀",
        "신약성경에서 사랑에 대한",
        "구약성서에서 예언된",
        
        // Testament constraints - English
        "verses from the new testament",
        "passages in the old testament",
        "in the NT about love",
        "OT prophecies about",
        
        // Book groups - Korean
        "사복음서에서 사랑이 나온 구절",
        "복음서에서 예수님의 말씀",
        "모세오경에서 율법에 대한",
        "바울서신에서 믿음에 관한",
        "시가서에서 찬양에 대해",
        "대선지서에서 예언",
        "소선지서에서 심판",
        
        // Book groups - English
        "in the four gospels about",
        "from the pentateuch about",
        "pauline epistles on faith",
        "wisdom books about",
        
        // Single book - Korean
        "로마서에서 복음의 정의",
        "창세기에서 창조에 대한",
        "요한복음에서 영생에 관한",
        "시편에서 위로의 말씀",
        "잠언에서 지혜에 대해",
        "이사야에서 메시아 예언",
        
        // Single book - English
        "in Romans about justification",
        "in Genesis about creation",
        "from John about eternal life",
        "in Psalms about comfort",
        
        // Multiple books - Korean
        "이사야, 예레미야에서 구원이 언급된",
        "마태복음과 요한복음에서 기적",
        "고린도전서와 후서에서 교회에 대해",
        "에베소서, 빌립보서에서 기쁨"
    );

    // Prototype phrases with NO context constraint (general searches)
    private static final List<String> NO_CONTEXT_PROTOTYPES = List.of(
        "사랑에 대한 말씀",
        "용서에 관한 구절",
        "하나님의 은혜",
        "믿음의 의미",
        "소망에 대해",
        "평안을 주는 말씀",
        "위로의 구절",
        "verses about love",
        "what does the Bible say about forgiveness",
        "comfort in suffering",
        "faith and trust",
        "모세가 나오는 구절",
        "다윗이 언급된",
        "예루살렘이 나오는"
    );

    // Book groups mapping (Korean and English)
    private static final Map<String, List<String>> BOOK_GROUPS_KR = Map.ofEntries(
        // 사복음서 (Four Gospels)
        Map.entry("사복음서", List.of("마", "막", "눅", "요")),
        Map.entry("복음서", List.of("마", "막", "눅", "요")),
        
        // 모세오경 (Pentateuch)
        Map.entry("모세오경", List.of("창", "출", "레", "민", "신")),
        Map.entry("율법서", List.of("창", "출", "레", "민", "신")),
        
        // 역사서 (Historical Books)
        Map.entry("역사서", List.of("수", "삿", "룻", "삼상", "삼하", "왕상", "왕하", "대상", "대하", "스", "느", "에")),
        
        // 시가서 (Wisdom/Poetry Books)
        Map.entry("시가서", List.of("욥", "시", "잠", "전", "아")),
        Map.entry("지혜서", List.of("욥", "시", "잠", "전", "아")),
        
        // 대선지서 (Major Prophets)
        Map.entry("대선지서", List.of("사", "렘", "애", "겔", "단")),
        
        // 소선지서 (Minor Prophets)
        Map.entry("소선지서", List.of("호", "욜", "암", "옵", "욘", "미", "나", "합", "습", "학", "슥", "말")),
        
        // 바울서신 (Pauline Epistles)
        Map.entry("바울서신", List.of("롬", "고전", "고후", "갈", "엡", "빌", "골", "살전", "살후", "딤전", "딤후", "딛", "몬")),
        
        // 일반서신 (General Epistles)
        Map.entry("일반서신", List.of("히", "약", "벧전", "벧후", "요일", "요이", "요삼", "유")),
        Map.entry("공동서신", List.of("히", "약", "벧전", "벧후", "요일", "요이", "요삼", "유"))
    );

    private static final Map<String, List<String>> BOOK_GROUPS_EN = Map.ofEntries(
        Map.entry("four gospels", List.of("Matt", "Mark", "Luke", "John")),
        Map.entry("gospels", List.of("Matt", "Mark", "Luke", "John")),
        Map.entry("pentateuch", List.of("Gen", "Ex", "Lev", "Num", "Deut")),
        Map.entry("torah", List.of("Gen", "Ex", "Lev", "Num", "Deut")),
        Map.entry("wisdom books", List.of("Job", "Ps", "Prov", "Eccl", "Song")),
        Map.entry("major prophets", List.of("Isa", "Jer", "Lam", "Ezek", "Dan")),
        Map.entry("minor prophets", List.of("Hos", "Joel", "Amos", "Obad", "Jonah", "Mic", "Nah", "Hab", "Zeph", "Hag", "Zech", "Mal")),
        Map.entry("pauline epistles", List.of("Rom", "1Cor", "2Cor", "Gal", "Eph", "Phil", "Col", "1Thess", "2Thess", "1Tim", "2Tim", "Titus", "Philem")),
        Map.entry("general epistles", List.of("Heb", "Jas", "1Pet", "2Pet", "1John", "2John", "3John", "Jude"))
    );

    // Single book name mappings (Korean book name → book short)
    private static final Map<String, String> BOOK_NAMES_KR = Map.ofEntries(
        // Old Testament
        Map.entry("창세기", "창"), Map.entry("출애굽기", "출"), Map.entry("레위기", "레"),
        Map.entry("민수기", "민"), Map.entry("신명기", "신"), Map.entry("여호수아", "수"),
        Map.entry("사사기", "삿"), Map.entry("룻기", "룻"), Map.entry("사무엘상", "삼상"),
        Map.entry("사무엘하", "삼하"), Map.entry("열왕기상", "왕상"), Map.entry("열왕기하", "왕하"),
        Map.entry("역대상", "대상"), Map.entry("역대하", "대하"), Map.entry("에스라", "스"),
        Map.entry("느헤미야", "느"), Map.entry("에스더", "에"), Map.entry("욥기", "욥"),
        Map.entry("시편", "시"), Map.entry("잠언", "잠"), Map.entry("전도서", "전"),
        Map.entry("아가", "아"), Map.entry("이사야", "사"), Map.entry("예레미야", "렘"),
        Map.entry("예레미아", "렘"), Map.entry("애가", "애"), Map.entry("에스겔", "겔"),
        Map.entry("다니엘", "단"), Map.entry("호세아", "호"), Map.entry("요엘", "욜"),
        Map.entry("아모스", "암"), Map.entry("오바댜", "옵"), Map.entry("요나", "욘"),
        Map.entry("미가", "미"), Map.entry("나훔", "나"), Map.entry("하박국", "합"),
        Map.entry("스바냐", "습"), Map.entry("학개", "학"), Map.entry("스가랴", "슥"),
        Map.entry("말라기", "말"),
        
        // New Testament
        Map.entry("마태복음", "마"), Map.entry("마가복음", "막"), Map.entry("누가복음", "눅"),
        Map.entry("요한복음", "요"), Map.entry("사도행전", "행"), Map.entry("로마서", "롬"),
        Map.entry("고린도전서", "고전"), Map.entry("고린도후서", "고후"), Map.entry("갈라디아서", "갈"),
        Map.entry("에베소서", "엡"), Map.entry("빌립보서", "빌"), Map.entry("골로새서", "골"),
        Map.entry("데살로니가전서", "살전"), Map.entry("데살로니가후서", "살후"),
        Map.entry("디모데전서", "딤전"), Map.entry("디모데후서", "딤후"), Map.entry("디도서", "딛"),
        Map.entry("빌레몬서", "몬"), Map.entry("히브리서", "히"), Map.entry("야고보서", "약"),
        Map.entry("베드로전서", "벧전"), Map.entry("베드로후서", "벧후"),
        Map.entry("요한일서", "요일"), Map.entry("요한이서", "요이"), Map.entry("요한삼서", "요삼"),
        Map.entry("유다서", "유"), Map.entry("요한계시록", "계"), Map.entry("계시록", "계")
    );

    // English book names
    private static final Map<String, String> BOOK_NAMES_EN = Map.ofEntries(
        Map.entry("genesis", "Gen"), Map.entry("exodus", "Ex"), Map.entry("leviticus", "Lev"),
        Map.entry("numbers", "Num"), Map.entry("deuteronomy", "Deut"), Map.entry("joshua", "Josh"),
        Map.entry("judges", "Judg"), Map.entry("ruth", "Ruth"), Map.entry("1 samuel", "1Sam"),
        Map.entry("2 samuel", "2Sam"), Map.entry("1 kings", "1Kgs"), Map.entry("2 kings", "2Kgs"),
        Map.entry("1 chronicles", "1Chr"), Map.entry("2 chronicles", "2Chr"), Map.entry("ezra", "Ezra"),
        Map.entry("nehemiah", "Neh"), Map.entry("esther", "Esth"), Map.entry("job", "Job"),
        Map.entry("psalms", "Ps"), Map.entry("proverbs", "Prov"), Map.entry("ecclesiastes", "Eccl"),
        Map.entry("song of solomon", "Song"), Map.entry("isaiah", "Isa"), Map.entry("jeremiah", "Jer"),
        Map.entry("lamentations", "Lam"), Map.entry("ezekiel", "Ezek"), Map.entry("daniel", "Dan"),
        Map.entry("hosea", "Hos"), Map.entry("joel", "Joel"), Map.entry("amos", "Amos"),
        Map.entry("obadiah", "Obad"), Map.entry("jonah", "Jonah"), Map.entry("micah", "Mic"),
        Map.entry("nahum", "Nah"), Map.entry("habakkuk", "Hab"), Map.entry("zephaniah", "Zeph"),
        Map.entry("haggai", "Hag"), Map.entry("zechariah", "Zech"), Map.entry("malachi", "Mal"),
        Map.entry("matthew", "Matt"), Map.entry("mark", "Mark"), Map.entry("luke", "Luke"),
        Map.entry("john", "John"), Map.entry("acts", "Acts"), Map.entry("romans", "Rom"),
        Map.entry("1 corinthians", "1Cor"), Map.entry("2 corinthians", "2Cor"),
        Map.entry("galatians", "Gal"), Map.entry("ephesians", "Eph"), Map.entry("philippians", "Phil"),
        Map.entry("colossians", "Col"), Map.entry("1 thessalonians", "1Thess"),
        Map.entry("2 thessalonians", "2Thess"), Map.entry("1 timothy", "1Tim"),
        Map.entry("2 timothy", "2Tim"), Map.entry("titus", "Titus"), Map.entry("philemon", "Philem"),
        Map.entry("hebrews", "Heb"), Map.entry("james", "Jas"), Map.entry("1 peter", "1Pet"),
        Map.entry("2 peter", "2Pet"), Map.entry("1 john", "1John"), Map.entry("2 john", "2John"),
        Map.entry("3 john", "3John"), Map.entry("jude", "Jude"), Map.entry("revelation", "Rev")
    );

    // Regex patterns for scope extraction
    // Korean: "XXX에서", "XXX의", "XXX에", "XXX에 있는", "XXX에서 나오는"
    private static final Pattern KOREAN_SCOPE_PATTERN = Pattern.compile(
        "^(.+?)(?:에서|에|의|에서 나오는|에 있는|에 나오는)\\s+(.+)$"
    );
    
    // English: "in XXX", "from XXX", "in the XXX"
    private static final Pattern ENGLISH_SCOPE_PATTERN = Pattern.compile(
        "^(?:in|from|in the|from the)\\s+(.+?)\\s+(?:about|on|concerning|regarding)?\\s*(.+)$",
        Pattern.CASE_INSENSITIVE
    );

    // Testament patterns
    private static final Pattern KOREAN_OT_PATTERN = Pattern.compile(
        "구약(?:성경|성서)?", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern KOREAN_NT_PATTERN = Pattern.compile(
        "신약(?:성경|성서)?", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ENGLISH_OT_PATTERN = Pattern.compile(
        "(?:old testament|OT)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ENGLISH_NT_PATTERN = Pattern.compile(
        "(?:new testament|NT)", Pattern.CASE_INSENSITIVE
    );

    // Similarity thresholds
    private static final double CONTEXT_THRESHOLD = 0.50;
    private static final double DIFFERENCE_THRESHOLD = 0.08;

    @PostConstruct
    public void initializePrototypes() {
        log.info("Initializing context classifier with {} context and {} no-context prototypes",
                CONTEXT_PROTOTYPES.size(), NO_CONTEXT_PROTOTYPES.size());
        
        long startTime = System.currentTimeMillis();

        // Pre-compute context prototype embeddings
        for (String prototype : CONTEXT_PROTOTYPES) {
            contextPrototypeEmbeddings.add(embeddingModel.embed(prototype).content());
        }

        // Pre-compute no-context prototype embeddings
        for (String prototype : NO_CONTEXT_PROTOTYPES) {
            noContextPrototypeEmbeddings.add(embeddingModel.embed(prototype).content());
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Context classifier initialized in {}ms", duration);
    }

    /**
     * Extract context (book scope) from a search query.
     * 
     * @param query The user's search query
     * @return ContextResult with extracted scope and cleaned query
     */
    public ContextResult extract(String query) {
        if (query == null || query.isBlank()) {
            return ContextResult.noContext(query);
        }

        String trimmed = query.trim();

        // Step 1: Use embedding similarity to detect if query has context constraint
        Embedding queryEmbedding = embeddingModel.embed(trimmed).content();
        double contextSimilarity = calculateAverageSimilarity(queryEmbedding, contextPrototypeEmbeddings);
        double noContextSimilarity = calculateAverageSimilarity(queryEmbedding, noContextPrototypeEmbeddings);
        
        double difference = contextSimilarity - noContextSimilarity;
        
        log.debug("Context scores for '{}': context={:.3f}, no-context={:.3f}, diff={:.3f}", 
                 trimmed, contextSimilarity, noContextSimilarity, difference);

        // If clearly no context, return early
        if (noContextSimilarity > contextSimilarity && -difference > DIFFERENCE_THRESHOLD) {
            log.debug("No context detected for query: '{}'", trimmed);
            return ContextResult.noContext(trimmed);
        }

        // Step 2: Try to extract scope using patterns
        ContextResult result = tryExtractScope(trimmed, contextSimilarity);
        
        if (result != null && result.hasContext()) {
            log.info("Context extracted: {} -> {} (confidence: {:.0f}%)", 
                    trimmed, result.contextDescription(), result.confidence() * 100);
            return result;
        }

        // No scope extracted
        return ContextResult.noContext(trimmed);
    }

    /**
     * Try to extract scope from query using regex patterns.
     */
    private ContextResult tryExtractScope(String query, double confidence) {
        // Try Korean pattern first
        Matcher koreanMatcher = KOREAN_SCOPE_PATTERN.matcher(query);
        if (koreanMatcher.matches()) {
            String scopePart = koreanMatcher.group(1).trim();
            String searchPart = koreanMatcher.group(2).trim();
            
            ContextResult result = parseScopePart(scopePart, searchPart, query, confidence, true);
            if (result != null) {
                return result;
            }
        }

        // Try English pattern
        Matcher englishMatcher = ENGLISH_SCOPE_PATTERN.matcher(query);
        if (englishMatcher.matches()) {
            String scopePart = englishMatcher.group(1).trim();
            String searchPart = englishMatcher.group(2).trim();
            
            ContextResult result = parseScopePart(scopePart, searchPart, query, confidence, false);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    /**
     * Parse the scope part and determine the context type.
     */
    private ContextResult parseScopePart(String scopePart, String searchPart, 
                                          String originalQuery, double confidence, boolean isKorean) {
        // Check for testament
        if (isKorean) {
            if (KOREAN_OT_PATTERN.matcher(scopePart).find()) {
                return new ContextResult(
                    ContextType.TESTAMENT, null, 1, searchPart, originalQuery,
                    "구약 (Old Testament)", confidence
                );
            }
            if (KOREAN_NT_PATTERN.matcher(scopePart).find()) {
                return new ContextResult(
                    ContextType.TESTAMENT, null, 2, searchPart, originalQuery,
                    "신약 (New Testament)", confidence
                );
            }
        } else {
            if (ENGLISH_OT_PATTERN.matcher(scopePart).find()) {
                return new ContextResult(
                    ContextType.TESTAMENT, null, 1, searchPart, originalQuery,
                    "Old Testament", confidence
                );
            }
            if (ENGLISH_NT_PATTERN.matcher(scopePart).find()) {
                return new ContextResult(
                    ContextType.TESTAMENT, null, 2, searchPart, originalQuery,
                    "New Testament", confidence
                );
            }
        }

        // Check for book groups
        Map<String, List<String>> bookGroups = isKorean ? BOOK_GROUPS_KR : BOOK_GROUPS_EN;
        for (Map.Entry<String, List<String>> entry : bookGroups.entrySet()) {
            if (scopePart.toLowerCase().contains(entry.getKey().toLowerCase())) {
                return new ContextResult(
                    ContextType.BOOK_GROUP, entry.getValue(), null, searchPart, originalQuery,
                    entry.getKey(), confidence
                );
            }
        }

        // Check for multiple books (comma or 과/와 separated)
        List<String> multipleBooks = parseMultipleBooks(scopePart, isKorean);
        if (multipleBooks.size() > 1) {
            String description = String.join(", ", multipleBooks);
            return new ContextResult(
                ContextType.MULTIPLE_BOOKS, multipleBooks, null, searchPart, originalQuery,
                description, confidence
            );
        }

        // Check for single book
        Map<String, String> bookNames = isKorean ? BOOK_NAMES_KR : BOOK_NAMES_EN;
        for (Map.Entry<String, String> entry : bookNames.entrySet()) {
            if (scopePart.toLowerCase().contains(entry.getKey().toLowerCase())) {
                return new ContextResult(
                    ContextType.SINGLE_BOOK, List.of(entry.getValue()), null, searchPart, originalQuery,
                    entry.getKey(), confidence
                );
            }
        }

        return null;
    }

    /**
     * Parse multiple book names from scope part.
     * Handles: "이사야, 예레미야" or "마태복음과 요한복음" or "Isaiah and Jeremiah"
     */
    private List<String> parseMultipleBooks(String scopePart, boolean isKorean) {
        List<String> books = new ArrayList<>();
        
        // Split by comma, 과, 와, and, &
        String[] parts = scopePart.split("[,과와]|\\s+and\\s+|\\s*&\\s*");
        
        Map<String, String> bookNames = isKorean ? BOOK_NAMES_KR : BOOK_NAMES_EN;
        
        for (String part : parts) {
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) continue;
            
            // Try to match book name
            for (Map.Entry<String, String> entry : bookNames.entrySet()) {
                if (trimmedPart.toLowerCase().contains(entry.getKey().toLowerCase())) {
                    books.add(entry.getValue());
                    break;
                }
            }
        }
        
        return books;
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
     * Get classifier statistics for debugging.
     */
    public String getStats() {
        return String.format("ContextClassifier: %d context prototypes, %d no-context prototypes",
                contextPrototypeEmbeddings.size(), noContextPrototypeEmbeddings.size());
    }
}
