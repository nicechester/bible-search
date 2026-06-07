package io.github.nicechester.biblesearch.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.*;

@Slf4j
@Service
public class CommentaryService {

    private static final Map<String, String> BOOK_MAP = new HashMap<>();

    static {
        // ASV bookShort / KRV bookShort → commentary DB book name
        String[][] mappings = {
            {"창","Gen","genesis"}, {"출","Ex","exodus"}, {"레","Lev","leviticus"},
            {"민","Num","numbers"}, {"신","Deut","deuteronomy"}, {"수","Josh","joshua"},
            {"삿","Judg","judges"}, {"룻","Ruth","ruth"}, {"삼상","1 Sam","1samuel"},
            {"삼하","2 Sam","2samuel"}, {"왕상","1 Kgs","1kings"}, {"왕하","2 Kgs","2kings"},
            {"대상","1 Chr","1chronicles"}, {"대하","2 Chr","2chronicles"}, {"스","Ezra","ezra"},
            {"느","Neh","nehemiah"}, {"에","Esth","esther"}, {"욥","Job","job"},
            {"시","Ps","psalms"}, {"잠","Prov","proverbs"}, {"전","Eccl","ecclesiastes"},
            {"아","Song","songofsolomon"}, {"사","Isa","isaiah"}, {"렘","Jer","jeremiah"},
            {"애","Lam","lamentations"}, {"겔","Ezek","ezekiel"}, {"단","Dan","daniel"},
            {"호","Hos","hosea"}, {"욜","Joel","joel"}, {"암","Amos","amos"},
            {"옵","Obad","obadiah"}, {"욘","Jonah","jonah"}, {"미","Mic","micah"},
            {"나","Nah","nahum"}, {"합","Hab","habakkuk"}, {"습","Zeph","zephaniah"},
            {"학","Hag","haggai"}, {"슥","Zech","zechariah"}, {"말","Mal","malachi"},
            {"마","Matt","matthew"}, {"막","Mark","mark"}, {"눅","Luke","luke"},
            {"요","John","john"}, {"행","Acts","acts"}, {"롬","Rom","romans"},
            {"고전","1 Cor","1corinthians"}, {"고후","2 Cor","2corinthians"}, {"갈","Gal","galatians"},
            {"엡","Eph","ephesians"}, {"빌","Phil","philippians"}, {"골","Col","colossians"},
            {"살전","1 Thess","1thessalonians"}, {"살후","2 Thess","2thessalonians"},
            {"딤전","1 Tim","1timothy"}, {"딤후","2 Tim","2timothy"}, {"딛","Titus","titus"},
            {"몬","Philem","philemon"}, {"히","Heb","hebrews"}, {"약","James","james"},
            {"벧전","1 Pet","1peter"}, {"벧후","2 Pet","2peter"}, {"요일","1 John","1john"},
            {"요이","2 John","2john"}, {"요삼","3 John","3john"}, {"유","Jude","jude"},
            {"계","Rev","revelation"}
        };
        for (String[] m : mappings) {
            BOOK_MAP.put(m[0].toLowerCase(), m[2]); // KRV short
            BOOK_MAP.put(m[1].toLowerCase(), m[2]); // ASV short
            BOOK_MAP.put(m[1].toLowerCase().replace(" ", ""), m[2]);
        }
    }

    @Value("${bible.commentary.path:classpath:commentaries/commentaries.sqlite}")
    private String dbPath;

    private final ResourceLoader resourceLoader;
    private Connection connection;

    public CommentaryService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() throws Exception {
        Resource resource = resourceLoader.getResource(dbPath);
        if (!resource.exists()) {
            log.warn("Commentary database not found: {}", dbPath);
            return;
        }
        String resolvedPath;
        try {
            resolvedPath = resource.getFile().getAbsolutePath();
        } catch (Exception e) {
            try (InputStream is = resource.getInputStream()) {
                Path tmp = Files.createTempFile("commentaries", ".sqlite");
                Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                resolvedPath = tmp.toString();
            }
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + resolvedPath + "?open_mode=1");
        log.info("Commentary database loaded: {}", resolvedPath);
    }

    @PreDestroy
    public void close() throws Exception {
        if (connection != null && !connection.isClosed()) connection.close();
    }

    public record Commentary(String fatherName, String sourceTitle, String text, String sourceUrl) {}

    public List<Commentary> getCommentaries(String bookShort, int chapter, int verse) {
        if (connection == null) return List.of();

        String dbBook = BOOK_MAP.get(bookShort.toLowerCase());
        if (dbBook == null) {
            log.debug("No commentary book mapping for: {}", bookShort);
            return List.of();
        }

        long location = (long) chapter * 1_000_000 + verse;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT father_name, source_title, txt, source_url FROM commentary " +
                "WHERE book = ? AND location_start <= ? AND location_end >= ? " +
                "ORDER BY ts LIMIT 20")) {
            ps.setString(1, dbBook);
            ps.setLong(2, location);
            ps.setLong(3, location);

            List<Commentary> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Commentary(
                        rs.getString("father_name"),
                        rs.getString("source_title"),
                        rs.getString("txt"),
                        rs.getString("source_url")
                    ));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("Failed to query commentaries: {}", e.getMessage());
            return List.of();
        }
    }
}
