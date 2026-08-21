package org.openfilz.dms.e2e.signature;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.e2e.TestContainersBaseConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Default deployment: {@code openfilz.signature.active=false}. The controllers are mapped but
 * answer 404, the settings flag is off, and the public chain is inert (the request falls through
 * to the default chain — here no-auth — and still gets the controller's 404).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class SignatureDisabledIT extends TestContainersBaseConfig {

    SignatureDisabledIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void everything_answers_404_when_off() {
        String sig = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SIGNATURES;
        String tpl = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SIGNATURE_TEMPLATES;
        String pub = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_PUBLIC_SIGNATURES;
        getWebTestClient().get().uri(sig).exchange().expectStatus().isNotFound();
        getWebTestClient().get().uri(sig + "/to-sign").exchange().expectStatus().isNotFound();
        getWebTestClient().get().uri(tpl).exchange().expectStatus().isNotFound();
        getWebTestClient().get().uri(u -> u.path(pub).queryParam("token", "x").build()).exchange().expectStatus().isNotFound();
        getWebTestClient().post().uri(u -> u.path(pub + "/otp/request").queryParam("token", "x").build()).exchange().expectStatus().isNotFound();

        Settings settings = getWebTestClient().get().uri("/api/v1/settings")
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings.signatureActive()).isFalse();
    }
}
