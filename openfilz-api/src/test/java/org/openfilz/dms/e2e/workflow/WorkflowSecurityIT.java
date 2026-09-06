package org.openfilz.dms.e2e.workflow;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.workflow.CancelInstanceRequest;
import org.openfilz.dms.dto.workflow.StartWorkflowRequest;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDTO;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/** Role gates of {@code AbstractSecurityService.isWorkflowAuthorized} plus the engine's own candidate / initiator checks. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class WorkflowSecurityIT extends AbstractWorkflowIT {

    WorkflowSecurityIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void anonymous_is_401_and_reader_may_only_read_and_complete() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String reader = getAccessToken(READER);
        getWebTestClient().get().uri(DEF).exchange().expectStatus().isUnauthorized();
        getWebTestClient().get().uri(TASKS + "/mine").exchange().expectStatus().isUnauthorized();

        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Sec"), approvalSpec(users(READER_EMAIL), List.of())));
        UUID doc = upload(contributor, null);

        // Reader: reads OK, writes refused by the security chain.
        getWebTestClient().get().uri(DEF).header(HttpHeaders.AUTHORIZATION, "Bearer " + reader).exchange().expectStatus().isOk();
        getWebTestClient().get().uri(TASKS + "/mine").header(HttpHeaders.AUTHORIZATION, "Bearer " + reader).exchange().expectStatus().isOk();
        createDefinitionRaw(reader, definition(unique("Sec2"), approvalSpec(users(READER_EMAIL), List.of()))).expectStatus().isForbidden();
        startRaw(reader, new StartWorkflowRequest(def.id(), doc, null, "submit", null)).expectStatus().isForbidden();
        getWebTestClient().delete().uri(DEF + "/" + def.id()).header(HttpHeaders.AUTHORIZATION, "Bearer " + reader)
                .exchange().expectStatus().isForbidden();

        // A contributor starts it; the reader, as candidate, completes it (no CONTRIBUTOR needed).
        WorkflowInstanceDTO started = start(contributor, def.id(), doc, "submit");
        authed(getWebTestClient().post().uri(INST + "/" + started.id() + "/cancel"), reader, new CancelInstanceRequest(null))
                .exchange().expectStatus().isForbidden();
        complete(reader, started.currentTask().id(), "approve", null);
    }

    @Test
    void cleaner_alone_may_not_delete_definitions() {
        String contributor = getAccessToken(CONTRIBUTOR);
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Del"), approvalSpec(users(ADMIN_EMAIL), List.of())));
        getWebTestClient().delete().uri(DEF + "/" + def.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken("cleaner-user"))
                .exchange().expectStatus().isForbidden();
        getWebTestClient().delete().uri(DEF + "/" + def.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isNoContent();
    }
}
