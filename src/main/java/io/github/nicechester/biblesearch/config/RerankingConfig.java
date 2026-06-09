package io.github.nicechester.biblesearch.config;

import io.github.nicechester.biblesearch.service.HeuristicRerankingService;
import io.github.nicechester.biblesearch.service.OnnxRerankingService;
import io.github.nicechester.biblesearch.service.RerankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for reranking service.
 * Wires HeuristicRerankingService as the default implementation,
 * with OnnxRerankingService as an optional overlay when enabled.
 */
@Slf4j
@Configuration
public class RerankingConfig {

    @Bean
    @Primary
    public RerankingService rerankingService(OnnxRerankingService onnxRerankingService) {
        // Return the ONNX service, which has fallback logic built-in
        log.info("Reranking service initialized (with ONNX fallback to heuristic)");
        return onnxRerankingService;
    }
}
