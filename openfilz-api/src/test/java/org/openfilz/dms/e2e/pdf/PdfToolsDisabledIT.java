package org.openfilz.dms.e2e.pdf;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.e2e.TestContainersBaseConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * {@code openfilz.pdf-tools.active=false}: the controller stays mapped but answers 404 on every
 * route, and the settings flag is off so the frontend hides the actions. Runs under no-auth to
 * prove the toggle, not the security chain, produces the 404.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class PdfToolsDisabledIT extends TestContainersBaseConfig {

    PdfToolsDisabledIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void pdfToolsOff(DynamicPropertyRegistry registry) {
        registry.add("openfilz.pdf-tools.active", () -> false);
    }

    @Test
    void everything_answers_404_when_off() {
        String pdf = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_PDF;
        getWebTestClient().get().uri(pdf + "/" + UUID.randomUUID() + "/info").exchange().expectStatus().isNotFound();
        for (String route : new String[]{"/merge", "/split", "/organize", "/rotate"}) {
            getWebTestClient().post().uri(pdf + route).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isNotFound();
        }

        Settings settings = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/settings")
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings.pdfToolsActive()).isFalse();
    }
}
