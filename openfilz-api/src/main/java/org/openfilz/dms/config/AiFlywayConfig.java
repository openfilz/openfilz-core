package org.openfilz.dms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

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

    // Runs before every other FlywayConfigurationCustomizer so the AI location is present in
    // configuration.getLocations() by the time they read it. The GraalVM native-image workaround
    // (NativeFlywayMigrationConfig, enterprise modules) enumerates the locations itself and hands
    // Flyway an explicit file list; if it ran first it would never see db/ai-migration, and
    // Flyway's own scanner cannot recover it in a native image ("unsupported protocol: resource").
    // The AI migrations would then be silently skipped and the app would fail at startup on the
    // missing ai_embedding_registry / vector_store tables.
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public FlywayConfigurationCustomizer aiFlywayCustomizer(
            // Runtime property read (not a bean condition) — native-image safe
            @Value("${openfilz.ai.active:false}") boolean aiActive) {
        return configuration -> {
            if (!aiActive) {
                return;
            }
            Stream<String> existing = configuration.getLocations().length > 0
                    ? Arrays.stream(configuration.getLocations()).map(location -> location.getDescriptor())
                    : Stream.of(DEFAULT_MIGRATION_LOCATION);

            configuration.locations(Stream.concat(existing, Stream.of(AI_MIGRATION_LOCATION))
                    .toArray(String[]::new));
        };
    }
}
