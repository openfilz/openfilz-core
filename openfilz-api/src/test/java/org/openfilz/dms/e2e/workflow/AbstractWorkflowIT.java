package org.openfilz.dms.e2e.workflow;

import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.dto.workflow.CompleteTaskRequest;
import org.openfilz.dms.dto.workflow.SaveWorkflowDefinitionRequest;
import org.openfilz.dms.dto.workflow.StartWorkflowRequest;
import org.openfilz.dms.dto.workflow.WorkflowAction;
import org.openfilz.dms.dto.workflow.WorkflowAssignment;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDetailDTO;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import org.openfilz.dms.dto.workflow.WorkflowState;
import org.openfilz.dms.dto.workflow.WorkflowTaskDTO;
import org.openfilz.dms.dto.workflow.WorkflowTransition;
import org.openfilz.dms.e2e.TestContainersKeyCloakConfig;
import org.openfilz.dms.enums.WorkflowAssigneeType;
import org.openfilz.dms.enums.WorkflowStateKind;
import org.openfilz.dms.enums.WorkflowTransitionStyle;
import org.openfilz.dms.service.workflow.WorkflowMailer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared plumbing of the workflow ITs: feature on, Keycloak users, a capturing mailer, and
 * helpers that drive the feature through its REST API only.
 */
@Import(WorkflowTestConfig.class)
public abstract class AbstractWorkflowIT extends TestContainersKeyCloakConfig {

    protected static final String WF = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_WORKFLOWS;
    protected static final String DEF = WF + "/definitions";
    protected static final String INST = WF + "/instances";
    protected static final String TASKS = WF + "/tasks";

    protected static final String ADMIN = "admin-user";
    protected static final String ADMIN_EMAIL = "admin-user@test.com";
    protected static final String CONTRIBUTOR = "contributor-user";
    protected static final String CONTRIBUTOR_EMAIL = "contributor-user@test.com";
    protected static final String READER = "reader-user";
    protected static final String READER_EMAIL = "reader-user@test.com";

    @Autowired
    protected WorkflowMailer workflowMailer;

    protected AbstractWorkflowIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    protected CapturingWorkflowMailer mails() {
        return (CapturingWorkflowMailer) workflowMailer;
    }

    @DynamicPropertySource
    static void workflowProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("openfilz.workflows.active", () -> true);
        registry.add("openfilz.workflows.web-base-url", () -> "http://web.test/");
    }

    // ── spec builders ───────────────────────────────────────────────────

    protected static WorkflowTransition transition(String key, String label, String to, WorkflowTransitionStyle style, boolean requireComment) {
        return new WorkflowTransition(key, label, to, style, requireComment);
    }

    protected static WorkflowState state(String key, String label, WorkflowStateKind kind, WorkflowAssignment assignees,
                                         Integer dueInDays, List<WorkflowTransition> transitions, List<WorkflowAction> onEnter) {
        return new WorkflowState(key, label, kind, null, assignees, dueInDays, transitions, onEnter);
    }

    protected static WorkflowAssignment users(String... emails) {
        return new WorkflowAssignment(WorkflowAssigneeType.USERS, List.of(emails), null, null);
    }

    protected static WorkflowAssignment role(String role) {
        return new WorkflowAssignment(WorkflowAssigneeType.ROLE, null, role, null);
    }

    /** Draft → Pending approval (approvers) → Approved | Rejected (reject needs a comment). */
    protected static WorkflowSpec approvalSpec(WorkflowAssignment approvers, List<WorkflowAction> onApproved) {
        return new WorkflowSpec(List.of(
                state("draft", "Draft", WorkflowStateKind.START, null, null,
                        List.of(transition("submit", "Submit for approval", "pending", WorkflowTransitionStyle.PRIMARY, false)), List.of()),
                state("pending", "Pending approval", WorkflowStateKind.STEP, approvers, 3,
                        List.of(transition("approve", "Approve", "approved", WorkflowTransitionStyle.SUCCESS, false),
                                transition("reject", "Reject", "rejected", WorkflowTransitionStyle.DANGER, true)), List.of()),
                state("approved", "Approved", WorkflowStateKind.END, null, null, List.of(), onApproved),
                state("rejected", "Rejected", WorkflowStateKind.END, null, null, List.of(), List.of())));
    }

    protected static SaveWorkflowDefinitionRequest definition(String name, WorkflowSpec spec) {
        return new SaveWorkflowDefinitionRequest(name, null, true, spec, null);
    }

    protected static SaveWorkflowDefinitionRequest definition(String name, WorkflowSpec spec, List<UUID> triggerFolderIds) {
        return new SaveWorkflowDefinitionRequest(name, null, true, spec, triggerFolderIds);
    }

    // ── REST helpers ────────────────────────────────────────────────────

    protected WebTestClient.RequestHeadersSpec<?> authed(WebTestClient.RequestBodySpec spec, String token, Object body) {
        return spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    protected WorkflowDefinitionDTO createDefinition(String token, SaveWorkflowDefinitionRequest req) {
        WorkflowDefinitionDTO dto = authed(getWebTestClient().post().uri(DEF), token, req)
                .exchange().expectStatus().isCreated()
                .expectBody(WorkflowDefinitionDTO.class).returnResult().getResponseBody();
        assertThat(dto).isNotNull();
        return dto;
    }

    protected WebTestClient.ResponseSpec createDefinitionRaw(String token, Object req) {
        return authed(getWebTestClient().post().uri(DEF), token, req).exchange();
    }

    protected WorkflowInstanceDTO start(String token, UUID definitionId, UUID documentId, String transitionKey) {
        return start(token, definitionId, documentId, transitionKey, null, null);
    }

    protected WorkflowInstanceDTO start(String token, UUID definitionId, UUID documentId, String transitionKey,
                                        Map<String, List<String>> assignments, String comment) {
        WorkflowInstanceDTO dto = startRaw(token, new StartWorkflowRequest(definitionId, documentId, assignments, transitionKey, comment))
                .expectStatus().isCreated()
                .expectBody(WorkflowInstanceDTO.class).returnResult().getResponseBody();
        assertThat(dto).isNotNull();
        return dto;
    }

    protected WebTestClient.ResponseSpec startRaw(String token, StartWorkflowRequest req) {
        return authed(getWebTestClient().post().uri(INST), token, req).exchange();
    }

    protected WorkflowInstanceDetailDTO getInstance(String token, UUID id) {
        return getWebTestClient().get().uri(INST + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(WorkflowInstanceDetailDTO.class).returnResult().getResponseBody();
    }

    protected List<WorkflowTaskDTO> myTasks(String token) {
        return getWebTestClient().get().uri(TASKS + "/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBodyList(WorkflowTaskDTO.class).returnResult().getResponseBody();
    }

    protected WebTestClient.ResponseSpec completeRaw(String token, UUID taskId, String transitionKey, String comment) {
        return authed(getWebTestClient().post().uri(TASKS + "/" + taskId + "/complete"), token,
                new CompleteTaskRequest(transitionKey, comment)).exchange();
    }

    protected WorkflowInstanceDTO complete(String token, UUID taskId, String transitionKey, String comment) {
        WorkflowInstanceDTO dto = completeRaw(token, taskId, transitionKey, comment)
                .expectStatus().isOk()
                .expectBody(WorkflowInstanceDTO.class).returnResult().getResponseBody();
        assertThat(dto).isNotNull();
        return dto;
    }

    protected UUID upload(String token, UUID parentFolderId) {
        MultipartBodyBuilder builder = newFileBuilder("test.txt");
        if (parentFolderId != null) {
            builder.part("parentFolderId", parentFolderId.toString());
        }
        UploadResponse resp = getWebTestClient().post()
                .uri(u -> u.path(RestApiVersion.API_PREFIX + "/documents/upload").queryParam("allowDuplicateFileNames", true).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertThat(resp).isNotNull();
        return resp.id();
    }

    protected UUID createFolder(String token, String name) {
        FolderResponse resp = authed(getWebTestClient().post().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FOLDERS), token,
                new CreateFolderRequest(name + "-" + UUID.randomUUID(), null))
                .exchange().expectStatus().isCreated()
                .expectBody(FolderResponse.class).returnResult().getResponseBody();
        assertThat(resp).isNotNull();
        return resp.id();
    }

    protected static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
