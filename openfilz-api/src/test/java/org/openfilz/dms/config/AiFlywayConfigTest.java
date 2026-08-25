package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiFlywayConfigTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    /**
     * Flyway scans a location recursively, so an AI migration parked under {@code db/migration}
     * would run on every deployment however {@code openfilz.ai.active} is set — and its
     * {@code CREATE EXTENSION vector} fails on a stock PostgreSQL image. Keeping it in a sibling
     * directory is what makes {@link AiFlywayConfig}'s condition mean anything.
     */
    @Test
    void aiMigrationIsNotNestedInsideTheDefaultLocation() throws Exception {
        Path aiMigrations = RESOURCES.resolve(
                AiFlywayConfig.AI_MIGRATION_LOCATION.replace("classpath:", ""));

        assertTrue(Files.isDirectory(aiMigrations), aiMigrations + " should hold the AI migrations");
        assertFalse(aiMigrations.startsWith(RESOURCES.resolve("db/migration")),
                "AI migrations must not live under the default Flyway location — Flyway scans it recursively");

        try (var defaultLocation = Files.walk(RESOURCES.resolve("db/migration"))) {
            assertFalse(defaultLocation.anyMatch(p -> p.getFileName().toString().contains("add_ai_support")),
                    "the AI migration leaked back into the default Flyway location");
        }
    }

    /**
     * The GraalVM native-image Flyway workaround (NativeFlywayMigrationConfig, enterprise modules)
     * reads {@code configuration.getLocations()} and freezes it into an explicit resource list,
     * so this customizer must have contributed the AI location before it runs. When it did not,
     * the AI migrations were silently skipped in native images and startup failed on the missing
     * {@code ai_embedding_registry} table — with Flyway cheerfully reporting "Schema is up to date".
     */
    @Test
    void aiCustomizerRunsBeforeAnyLocationConsumingCustomizer() throws Exception {
        Order order = AiFlywayConfig.class
                .getDeclaredMethod("aiFlywayCustomizer", boolean.class)
                .getAnnotation(Order.class);

        assertNotNull(order, "aiFlywayCustomizer must declare @Order — it contributes a Flyway location");
        assertEquals(Ordered.HIGHEST_PRECEDENCE, order.value(),
                "location contributors must run before customizers that read getLocations()");
    }
}
