package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The AI DataSource must be a SimpleDriverDataSource, not whatever pool the classpath offers.
 * <p>
 * HikariCP arrives transitively via spring-boot-starter-flyway, so an unpinned DataSourceBuilder
 * hands back a HikariDataSource — and that pool cannot open a connection in the EE native image,
 * which crash-looped the API at startup in v1.8.5. SimpleDriverDataSource is the type Boot gives
 * Flyway, which connects fine in that same image.
 */
class AiDataSourceTypeTest {

    private final AiConfig aiConfig = new AiConfig();

    @Test
    void aiDataSourceIsAlwaysASimpleDriverDataSource() {
        DataSource dataSource = aiConfig.aiDataSource(
                "jdbc:postgresql://localhost:5432/openfilz", "openfilz", "secret");

        // HikariCP is on the classpath (spring-boot-starter-flyway), so an unpinned
        // DataSourceBuilder hands back a HikariDataSource here — the v1.8.5 production shape.
        assertInstanceOf(SimpleDriverDataSource.class, dataSource);
    }

    @Test
    void aiDataSourceCarriesTheFlywayCredentials() {
        SimpleDriverDataSource dataSource = (SimpleDriverDataSource) aiConfig.aiDataSource(
                "jdbc:postgresql://postgres-ee:5432/openfilz", "openfilz", "secret");

        assertEquals("jdbc:postgresql://postgres-ee:5432/openfilz", dataSource.getUrl());
        assertEquals("openfilz", dataSource.getUsername());
        assertEquals("secret", dataSource.getPassword());
    }
}
