package org.openfilz.dms.e2e;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An image must refuse to start on a database migrated by a newer release. Flyway's default
 * {@code ignoreMigrationPatterns} is {@code *:future}, which ignores applied migrations the image does not know, so an
 * older image used to start silently on a newer schema. {@code application.yml} now tolerates only pending migrations.
 *
 * <p>The test reads the patterns from the real {@code application.yml} (placeholders resolved, so the default of
 * {@code OPENFILZ_FLYWAY_IGNORE_MIGRATION_PATTERNS} is what is checked), migrates an empty database, then records one
 * migration newer than any the image ships, as a newer release would have.
 */
@Testcontainers
class FlywayNewerDatabaseGuardIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Test
    void refusesADatabaseMigratedByANewerRelease() throws Exception {
        List<String> patterns = configuredIgnorePatterns();
        assertThat(patterns).doesNotContain("*:future").isNotEmpty();

        flyway(patterns).migrate();
        recordFutureMigration();

        assertThatThrownBy(() -> flyway(patterns).migrate())
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("999999.1");
        // Flyway's own default lets the same database through: the setting is what refuses it.
        assertThatCode(() -> flyway(List.of("*:future")).migrate()).doesNotThrowAnyException();
    }

    private static List<String> configuredIgnorePatterns() throws Exception {
        // application.yml alone: a build may override the property for its tests with a system property
        StandardEnvironment environment = new StandardEnvironment() {
            @Override
            protected void customizePropertySources(org.springframework.core.env.MutablePropertySources sources) {
            }
        };
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment)
                .bind("spring.flyway.ignore-migration-patterns", Bindable.listOf(String.class))
                .orElse(List.of());
    }

    private static Flyway flyway(List<String> patterns) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .outOfOrder(true)
                .ignoreMigrationPatterns(patterns.toArray(String[]::new))
                .load();
    }

    /** What a newer release leaves behind: an applied migration this image has no file for. */
    private static void recordFutureMigration() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO flyway_schema_history
                        (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                    SELECT COALESCE(MAX(installed_rank), 0) + 1, '999999.1', 'from a newer release', 'SQL',
                           'V999999_1__from_a_newer_release.sql', 0, current_user, 1, true
                    FROM flyway_schema_history""");
        }
    }
}
