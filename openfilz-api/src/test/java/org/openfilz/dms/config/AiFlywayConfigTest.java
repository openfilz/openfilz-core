package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
