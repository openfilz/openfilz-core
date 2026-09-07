package org.openfilz.dms.e2e.workflow;

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

/** Default deployment: {@code openfilz.workflows.active=false} — every endpoint answers 404 and the settings flag is off. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class WorkflowDisabledIT extends TestContainersBaseConfig {

    WorkflowDisabledIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void everything_answers_404_when_off() {
        String wf = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_WORKFLOWS;
        getWebTestClient().get().uri(wf + "/definitions").exchange().expectStatus().isNotFound();
        getWebTestClient().get().uri(wf + "/instances").exchange().expectStatus().isNotFound();
        getWebTestClient().get().uri(wf + "/tasks/mine").exchange().expectStatus().isNotFound();
        getWebTestClient().get().uri(wf + "/tasks/mine/count").exchange().expectStatus().isNotFound();
        getWebTestClient().post().uri(wf + "/definitions/validate")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isNotFound();

        Settings settings = getWebTestClient().get().uri("/api/v1/settings")
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings.workflowsActive()).isFalse();
        assertThat(settings.workflowDesignerRoleRequired()).isFalse();
    }
}
