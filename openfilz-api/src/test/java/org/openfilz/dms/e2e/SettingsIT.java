package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.Settings;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for the /settings endpoint.
 * Tests various configurations of soft-delete and recycle-bin settings.
 */
@Slf4j
public class SettingsIT {

    /**
     * Tests when soft-delete is disabled (openfilz.soft-delete.active=false).
     * Expected: emptyBinInterval should be null.
     */
    @Nested
    @Testcontainers
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestConstructor(autowireMode = ALL)
    class SoftDeleteDisabledIT extends TestContainersBaseConfig {

        public SoftDeleteDisabledIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
            super(webTestClient, customJackson2JsonEncoder);
        }

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            registry.add("openfilz.soft-delete.active", () -> false);
            registry.add("openfilz.soft-delete.recycle-bin.enabled", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.autoCleanupInterval", () -> "30 days");
        }

        @Test
        void whenSoftDeleteDisabled_thenEmptyBinIntervalIsNull() {
            Settings settings = getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Settings.class)
                    .returnResult().getResponseBody();

            Assertions.assertNotNull(settings);
            Assertions.assertNull(settings.emptyBinInterval(),
                    "emptyBinInterval should be null when soft-delete is disabled");
            log.info("Test passed: emptyBinInterval is null when soft-delete is disabled");
        }
    }

    /**
     * Tests when soft-delete is enabled but recycle-bin is disabled.
     * Expected: emptyBinInterval should be null.
     */
    @Nested
    @Testcontainers
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestConstructor(autowireMode = ALL)
    class RecycleBinDisabledIT extends TestContainersBaseConfig {

        public RecycleBinDisabledIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
            super(webTestClient, customJackson2JsonEncoder);
        }

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            registry.add("openfilz.soft-delete.active", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.enabled", () -> false);
            registry.add("openfilz.soft-delete.recycle-bin.autoCleanupInterval", () -> "30 days");
        }

        @Test
        void whenRecycleBinDisabled_thenEmptyBinIntervalIsNull() {
            Settings settings = getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Settings.class)
                    .returnResult().getResponseBody();

            Assertions.assertNotNull(settings);
            Assertions.assertNull(settings.emptyBinInterval(),
                    "emptyBinInterval should be null when recycle-bin is disabled");
            log.info("Test passed: emptyBinInterval is null when recycle-bin is disabled");
        }
    }

    /**
     * Tests when both soft-delete and recycle-bin are enabled but autoCleanupInterval is zero.
     * Expected: emptyBinInterval should be null.
     */
    @Nested
    @Testcontainers
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestConstructor(autowireMode = ALL)
    class AutoCleanupIntervalZeroIT extends TestContainersBaseConfig {

        public AutoCleanupIntervalZeroIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
            super(webTestClient, customJackson2JsonEncoder);
        }

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            registry.add("openfilz.soft-delete.active", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.enabled", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.autoCleanupInterval", () -> "0");
        }

        @Test
        void whenAutoCleanupIntervalIsZero_thenEmptyBinIntervalIsNull() {
            Settings settings = getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Settings.class)
                    .returnResult().getResponseBody();

            Assertions.assertNotNull(settings);
            Assertions.assertNull(settings.emptyBinInterval(),
                    "emptyBinInterval should be null when autoCleanupInterval is 0");
            log.info("Test passed: emptyBinInterval is null when autoCleanupInterval is 0");
        }
    }

    /**
     * Tests when both soft-delete and recycle-bin are enabled but autoCleanupInterval is empty.
     * Expected: emptyBinInterval should be null.
     */
    @Nested
    @Testcontainers
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestConstructor(autowireMode = ALL)
    class AutoCleanupIntervalEmptyIT extends TestContainersBaseConfig {

        public AutoCleanupIntervalEmptyIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
            super(webTestClient, customJackson2JsonEncoder);
        }

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            registry.add("openfilz.soft-delete.active", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.enabled", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.autoCleanupInterval", () -> "");
        }

        @Test
        void whenAutoCleanupIntervalIsEmpty_thenEmptyBinIntervalIsNull() {
            Settings settings = getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Settings.class)
                    .returnResult().getResponseBody();

            Assertions.assertNotNull(settings);
            Assertions.assertNull(settings.emptyBinInterval(),
                    "emptyBinInterval should be null when autoCleanupInterval is empty");
            log.info("Test passed: emptyBinInterval is null when autoCleanupInterval is empty");
        }
    }

    /**
     * Tests when all settings are enabled with a valid autoCleanupInterval of 30 days.
     * Expected: emptyBinInterval should be 30.
     */
    @Nested
    @Testcontainers
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestConstructor(autowireMode = ALL)
    class AutoCleanupInterval30DaysIT extends TestContainersBaseConfig {

        public AutoCleanupInterval30DaysIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
            super(webTestClient, customJackson2JsonEncoder);
        }

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            registry.add("openfilz.soft-delete.active", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.enabled", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.autoCleanupInterval", () -> "30 days");
        }

        @Test
        void whenAutoCleanupIntervalIs30Days_thenEmptyBinIntervalIs30() {
            Settings settings = getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Settings.class)
                    .returnResult().getResponseBody();

            Assertions.assertNotNull(settings);
            Assertions.assertNotNull(settings.emptyBinInterval(),
                    "emptyBinInterval should not be null when all settings are enabled");
            Assertions.assertEquals(30, settings.emptyBinInterval(),
                    "emptyBinInterval should be 30 when autoCleanupInterval is '30 days'");
            log.info("Test passed: emptyBinInterval is 30 when autoCleanupInterval is '30 days'");
        }
    }

    /**
     * Tests when all settings are enabled with a valid autoCleanupInterval of 7 days.
     * Expected: emptyBinInterval should be 7.
     */
    @Nested
    @Testcontainers
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestConstructor(autowireMode = ALL)
    class AutoCleanupInterval7DaysIT extends TestContainersBaseConfig {

        public AutoCleanupInterval7DaysIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
            super(webTestClient, customJackson2JsonEncoder);
        }

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            registry.add("openfilz.soft-delete.active", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.enabled", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.autoCleanupInterval", () -> "7 days");
        }

        @Test
        void whenAutoCleanupIntervalIs7Days_thenEmptyBinIntervalIs7() {
            Settings settings = getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Settings.class)
                    .returnResult().getResponseBody();

            Assertions.assertNotNull(settings);
            Assertions.assertNotNull(settings.emptyBinInterval(),
                    "emptyBinInterval should not be null when all settings are enabled");
            Assertions.assertEquals(7, settings.emptyBinInterval(),
                    "emptyBinInterval should be 7 when autoCleanupInterval is '7 days'");
            log.info("Test passed: emptyBinInterval is 7 when autoCleanupInterval is '7 days'");
        }
    }

    /**
     * Tests when all settings are enabled with a valid autoCleanupInterval of 90 days.
     * Expected: emptyBinInterval should be 90.
     */
    @Nested
    @Testcontainers
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestConstructor(autowireMode = ALL)
    class AutoCleanupInterval90DaysIT extends TestContainersBaseConfig {

        public AutoCleanupInterval90DaysIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
            super(webTestClient, customJackson2JsonEncoder);
        }

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            registry.add("openfilz.soft-delete.active", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.enabled", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.autoCleanupInterval", () -> "90 days");
        }

        @Test
        void whenAutoCleanupIntervalIs90Days_thenEmptyBinIntervalIs90() {
            Settings settings = getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Settings.class)
                    .returnResult().getResponseBody();

            Assertions.assertNotNull(settings);
            Assertions.assertNotNull(settings.emptyBinInterval(),
                    "emptyBinInterval should not be null when all settings are enabled");
            Assertions.assertEquals(90, settings.emptyBinInterval(),
                    "emptyBinInterval should be 90 when autoCleanupInterval is '90 days'");
            log.info("Test passed: emptyBinInterval is 90 when autoCleanupInterval is '90 days'");
        }
    }

    /**
     * Tests when all settings are enabled with autoCleanupInterval as just a number (no unit).
     * Expected: emptyBinInterval should be the number value.
     */
    @Nested
    @Testcontainers
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestConstructor(autowireMode = ALL)
    class AutoCleanupIntervalNumberOnlyIT extends TestContainersBaseConfig {

        public AutoCleanupIntervalNumberOnlyIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
            super(webTestClient, customJackson2JsonEncoder);
        }

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
            registry.add("openfilz.soft-delete.active", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.enabled", () -> true);
            registry.add("openfilz.soft-delete.recycle-bin.autoCleanupInterval", () -> "14");
        }

        @Test
        void whenAutoCleanupIntervalIsNumberOnly_thenEmptyBinIntervalIsCorrect() {
            Settings settings = getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Settings.class)
                    .returnResult().getResponseBody();

            Assertions.assertNotNull(settings);
            Assertions.assertNotNull(settings.emptyBinInterval(),
                    "emptyBinInterval should not be null when autoCleanupInterval is a valid number");
            Assertions.assertEquals(14, settings.emptyBinInterval(),
                    "emptyBinInterval should be 14 when autoCleanupInterval is '14'");
            log.info("Test passed: emptyBinInterval is 14 when autoCleanupInterval is '14'");
        }
    }
}
