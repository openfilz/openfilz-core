package org.openfilz.dms.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conditionally adds the AI-specific Flyway migration location when AI is active.
 * When openfilz.ai.active=false (default), the AI migration (V1_4__add_ai_support.sql)
 * is not picked up, so no pgvector tables or extensions are created.
 */
@Configuration
public class AiFlywayConfig {

    @Bean
    @ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
    public FlywayConfigurationCustomizer aiFlywayCustomizer() {
        return (FluentConfiguration configuration) -> {
            String[] existing = configuration.getLocations().length > 0
                    ? java.util.Arrays.stream(configuration.getLocations())
                        .map(loc -> loc.getDescriptor())
                        .toArray(String[]::new)
                    : new String[]{"classpath:db/migration"};

            String[] withAi = java.util.Arrays.copyOf(existing, existing.length + 1);
            withAi[existing.length] = "classpath:db/migration/ai";
            configuration.locations(withAi);
        };
    }
}
