package org.openfilz.dms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Conditionally adds the AI-specific Flyway migration location when AI is active.
 * When openfilz.ai.active=false (default), the AI migration (V1_4__add_ai_support.sql)
 * is not picked up, so no pgvector tables or extensions are created.
 * <p>
 * The AI migration deliberately lives in {@code db/ai-migration} rather than under
 * {@code db/migration}: Flyway scans a location <em>recursively</em>, so a sub-directory of the
 * default location would be applied on every deployment regardless of this condition — and its
 * {@code CREATE EXTENSION vector} fails on a stock PostgreSQL image.
 */
@Configuration
public class AiFlywayConfig {

    static final String AI_MIGRATION_LOCATION = "classpath:db/ai-migration";

    private static final String DEFAULT_MIGRATION_LOCATION = "classpath:db/migration";

    @Bean
    @ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
    public FlywayConfigurationCustomizer aiFlywayCustomizer() {
        return configuration -> {
            Stream<String> existing = configuration.getLocations().length > 0
                    ? Arrays.stream(configuration.getLocations()).map(location -> location.getDescriptor())
                    : Stream.of(DEFAULT_MIGRATION_LOCATION);

            configuration.locations(Stream.concat(existing, Stream.of(AI_MIGRATION_LOCATION))
                    .toArray(String[]::new));
        };
    }
}
