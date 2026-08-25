package org.openfilz.dms.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers the AI Flyway migrations as GraalVM native image resources.
 *
 * Spring Boot's Flyway AOT processing only registers the locations it can see in
 * {@code spring.flyway.locations}, which is {@code classpath:db/migration} (see application.yml).
 * {@link AiFlywayConfig} appends {@code classpath:db/ai-migration} at <em>runtime</em>, long after
 * the image has been built, so without this registrar the SQL files are simply absent from the
 * native image: {@code ClassLoader.getResources("db/ai-migration")} returns nothing, the migrations
 * are silently skipped, and the app fails at startup on the missing {@code ai_embedding_registry} /
 * {@code vector_store} tables.
 *
 * Registration is build-time and unconditional — it only embeds three small SQL files. Whether the
 * migrations actually run stays a runtime decision gated by {@code openfilz.ai.active} in
 * {@link AiFlywayConfig}, which keeps {@code CREATE EXTENSION vector} off stock PostgreSQL images.
 */
public class AiMigrationRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // The directory itself: the native-image Flyway workaround lists it via URL.openStream().
        hints.resources().registerPattern("db/ai-migration");
        // The migration files Flyway then loads by name.
        hints.resources().registerPattern("db/ai-migration/*");
    }
}
