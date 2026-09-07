package org.openfilz.dms.e2e.workflow;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * {@code openfilz.workflows.require-designer-role=true}: definition writes need WORKFLOW_DESIGNER
 * (admin-user has it, contributor-user does not); starting and acting never do.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class WorkflowDesignerRoleIT extends AbstractWorkflowIT {

    WorkflowDesignerRoleIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void designerRole(DynamicPropertyRegistry registry) {
        registry.add("openfilz.workflows.require-designer-role", () -> true);
    }

    @Test
    void designer_role_gates_definition_writes_only() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        Settings settings = getWebTestClient().get().uri("/api/v1/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk().expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings.workflowDesignerRoleRequired()).isTrue();

        createDefinitionRaw(contributor, definition(unique("NoRole"), approvalSpec(users(ADMIN_EMAIL), List.of()))).expectStatus().isForbidden();
        // Validation is a read-like dry run: allowed for every contributor (the designer role is about persisting).
        authed(getWebTestClient().post().uri(DEF + "/validate"), contributor, definition(unique("Dry"), approvalSpec(users(ADMIN_EMAIL), List.of())))
                .exchange().expectStatus().isOk();

        WorkflowDefinitionDTO def = createDefinition(admin, definition(unique("WithRole"), approvalSpec(users(ADMIN_EMAIL), List.of())));
        UUID doc = upload(contributor, null);
        start(contributor, def.id(), doc, "submit");
        getWebTestClient().delete().uri(DEF + "/" + def.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isForbidden();
    }
}
