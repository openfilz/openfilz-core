package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.filing.AutoFileService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

/** Runtime selection of the smart-filing service (native-safe, see {@link DocumentInsightConfig}). */
@Slf4j
@Configuration
public class AutoFileConfig {

    @Bean
    @Primary
    public AutoFileService autoFileService(
            AiProperties aiProperties,
            @Lazy @Qualifier("defaultAutoFileService") AutoFileService realService,
            @Lazy @Qualifier("noOpAutoFileService") AutoFileService noOpService) {
        boolean active = aiProperties.isActive() && aiProperties.getAutoFile().isActive();
        if (aiProperties.getAutoFile().isActive() && !aiProperties.isActive()) {
            log.warn("openfilz.ai.auto-file.active is on but openfilz.ai.active is off — smart filing stays off");
        }
        log.info("Smart filing on upload: {}", active ? "ENABLED" : "disabled");
        return active ? realService : noOpService;
    }
}
