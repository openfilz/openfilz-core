package org.openfilz.dms.e2e.signature;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.signature.CloudSignatureSubscription;
import org.openfilz.dms.service.signature.CloudSubscriptionClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Settings-page surface of Cloud Signing: when the {@code openfilz-cloud} seal provider is
 * configured, {@code GET /settings} advertises it and {@code GET /signatures/cloud-subscription}
 * relays the tenant's plan + usage from sign.openfilz.com (stubbed here — the remote call has
 * its own client and contract test on the signing-api side).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(SignatureCloudSubscriptionIT.StubCloudSubscriptionConfig.class)
class SignatureCloudSubscriptionIT extends AbstractSignatureIT {

    private static final OffsetDateTime PERIOD_START =
            OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @TestConfiguration
    static class StubCloudSubscriptionConfig {
        @Bean
        @Primary
        CloudSubscriptionClient stubCloudSubscriptionClient() {
            return () -> Mono.just(new CloudSignatureSubscription(
                    "ACTIVE", "INCLUDED", 100, 12L, 88L,
                    PERIOD_START, PERIOD_START.plusMonths(1), false,
                    PERIOD_START.minusMonths(6)));
        }
    }

    SignatureCloudSubscriptionIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void cloudSealProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.signature.seal.provider", () -> "openfilz-cloud");
        registry.add("openfilz.signature.seal.cloud.api-key", () -> "test-tenant-api-key");
    }

    @Test
    void settings_advertise_cloud_signing_and_subscription_endpoint_relays_plan_and_usage() {
        String token = getAccessToken("admin-user");

        Settings settings = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        assertThat(settings.signatureCloudActive()).isTrue();

        CloudSignatureSubscription sub = getWebTestClient().get().uri(SIG + "/cloud-subscription")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(CloudSignatureSubscription.class).returnResult().getResponseBody();
        assertThat(sub).isNotNull();
        assertThat(sub.status()).isEqualTo("ACTIVE");
        assertThat(sub.billingMode()).isEqualTo("INCLUDED");
        assertThat(sub.monthlyQuota()).isEqualTo(100);
        assertThat(sub.usedThisMonth()).isEqualTo(12);
        assertThat(sub.remaining()).isEqualTo(88);
        assertThat(sub.periodEnd()).isEqualTo(PERIOD_START.plusMonths(1));
    }

    @Test
    void subscription_endpoint_requires_authentication() {
        getWebTestClient().get().uri(SIG + "/cloud-subscription")
                .exchange().expectStatus().isUnauthorized();
    }
}
