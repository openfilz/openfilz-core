package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.insight.DocumentInsightService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

/**
 * Runtime selection of the tier-2 insight service (native-safe: both implementations are in
 * the image, the property is read at startup, the unused one is never initialised).
 */
@Slf4j
@Configuration
public class DocumentInsightConfig {

    @Bean
    @Primary
    public DocumentInsightService documentInsightService(
            AiProperties aiProperties,
            @Lazy @Qualifier("aiDocumentInsightService") DocumentInsightService aiService,
            @Lazy @Qualifier("noOpDocumentInsightService") DocumentInsightService noOpService) {
        boolean active = aiProperties.isActive() && aiProperties.getInsights().isActive();
        if (aiProperties.getInsights().isActive() && !aiProperties.isActive()) {
            log.warn("openfilz.ai.insights.active is on but openfilz.ai.active is off — document insights stay off");
        }
        log.info("Document insights (tier 2, AI enrichment): {}", active ? "ENABLED" : "disabled");
        return active ? aiService : noOpService;
    }
}
