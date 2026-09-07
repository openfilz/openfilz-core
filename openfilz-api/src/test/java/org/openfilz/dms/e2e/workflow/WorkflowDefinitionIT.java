package org.openfilz.dms.e2e.workflow;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.workflow.SaveWorkflowDefinitionRequest;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import org.openfilz.dms.dto.workflow.WorkflowState;
import org.openfilz.dms.dto.workflow.WorkflowValidationResult;
import org.openfilz.dms.enums.WorkflowStateKind;
import org.openfilz.dms.enums.WorkflowTransitionStyle;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/** Definitions: CRUD, validation answers, name uniqueness, delete guard, settings flag. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class WorkflowDefinitionIT extends AbstractWorkflowIT {

    WorkflowDefinitionIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void settings_expose_the_feature() {
        Settings settings = getWebTestClient().get().uri("/api/v1/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken(READER))
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings.workflowsActive()).isTrue();
        assertThat(settings.workflowDesignerRoleRequired()).isFalse();
    }

    @Test
    void create_read_update_delete() {
        String token = getAccessToken(CONTRIBUTOR);
        String name = unique("Approval");
        WorkflowDefinitionDTO created = createDefinition(token, definition(name, approvalSpec(users("alice@test.com"), List.of())));
        assertThat(created.name()).isEqualTo(name);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.active()).isTrue();
        assertThat(created.createdBy()).isEqualTo(CONTRIBUTOR_EMAIL);
        assertThat(created.spec().states()).extracting(WorkflowState::key).containsExactly("draft", "pending", "approved", "rejected");
        assertThat(created.runningCount()).isZero();

        List<WorkflowDefinitionDTO> all = getWebTestClient().get().uri(DEF)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken(READER))
                .exchange().expectStatus().isOk()
                .expectBodyList(WorkflowDefinitionDTO.class).returnResult().getResponseBody();
        assertThat(all).extracting(WorkflowDefinitionDTO::id).contains(created.id());

        WorkflowDefinitionDTO updated = authed(getWebTestClient().put().uri(DEF + "/" + created.id()), token,
                new SaveWorkflowDefinitionRequest(name + " v2", "desc", false, created.spec(), null))
                .exchange().expectStatus().isOk()
                .expectBody(WorkflowDefinitionDTO.class).returnResult().getResponseBody();
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.active()).isFalse();
        assertThat(updated.description()).isEqualTo("desc");

        List<WorkflowDefinitionDTO> active = getWebTestClient().get().uri(u -> u.path(DEF).queryParam("active", true).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBodyList(WorkflowDefinitionDTO.class).returnResult().getResponseBody();
        assertThat(active).extracting(WorkflowDefinitionDTO::id).doesNotContain(created.id());

        getWebTestClient().delete().uri(DEF + "/" + created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isNoContent();
        getWebTestClient().get().uri(DEF + "/" + created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void invalid_definitions_are_refused_with_the_problem_list() {
        String token = getAccessToken(CONTRIBUTOR);
        WorkflowSpec broken = new WorkflowSpec(List.of(
                state("draft", "Draft", WorkflowStateKind.START, null, null,
                        List.of(transition("go", "Go", "nowhere", WorkflowTransitionStyle.PRIMARY, false)), List.of())));
        createDefinitionRaw(token, definition(unique("Broken"), broken))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.problems[?(@.code == 'UNKNOWN_TARGET')]").exists()
                .jsonPath("$.problems[?(@.code == 'NO_END')]").exists();

        WorkflowValidationResult dry = authed(getWebTestClient().post().uri(DEF + "/validate"), token, definition(unique("Dry"), broken))
                .exchange().expectStatus().isOk()
                .expectBody(WorkflowValidationResult.class).returnResult().getResponseBody();
        assertThat(dry.valid()).isFalse();
        assertThat(dry.problems()).isNotEmpty();

        WorkflowValidationResult ok = authed(getWebTestClient().post().uri(DEF + "/validate"), token,
                definition(unique("Ok"), approvalSpec(users("a@test.com"), List.of())))
                .exchange().expectStatus().isOk()
                .expectBody(WorkflowValidationResult.class).returnResult().getResponseBody();
        assertThat(ok.valid()).isTrue();
    }

    @Test
    void names_are_unique_case_insensitively() {
        String token = getAccessToken(CONTRIBUTOR);
        String name = unique("Unique");
        createDefinition(token, definition(name, approvalSpec(users("a@test.com"), List.of())));
        createDefinitionRaw(token, definition(name.toUpperCase(), approvalSpec(users("a@test.com"), List.of())))
                .expectStatus().isEqualTo(409);
    }

    @Test
    void a_definition_with_running_instances_cannot_be_deleted() {
        String token = getAccessToken(CONTRIBUTOR);
        WorkflowDefinitionDTO def = createDefinition(token, definition(unique("Busy"), approvalSpec(users(ADMIN_EMAIL), List.of())));
        UUID doc = upload(token, null);
        start(token, def.id(), doc, "submit");
        getWebTestClient().delete().uri(DEF + "/" + def.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isEqualTo(409);
        WorkflowDefinitionDTO reloaded = getWebTestClient().get().uri(DEF + "/" + def.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(WorkflowDefinitionDTO.class).returnResult().getResponseBody();
        assertThat(reloaded.runningCount()).isEqualTo(1);
    }
}
