package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.ReorganizationApplyRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.ReorganizationApplyResult;
import org.openfilz.dms.dto.response.ReorganizationPlanView;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The reorganisation loop end to end, through the real front-ends: the MCP tools
 * ({@code planReorganization} → {@code proposeReorganizationPlan} → {@code applyReorganizationPlan})
 * and the REST endpoint the chat proposal card uses ({@code /api/v1/ai/reorganization/{id}}).
 * State is created and verified through the public API only.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
class OrganizeAiToolsIT extends AbstractMcpIT {

    private static final String REORG = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/reorganization";
    private static final Pattern PLAN_ID = Pattern.compile("Plan ([0-9a-f-]{36})");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    OrganizeAiToolsIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void reorganizationProperties(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
        // The REST endpoint is gated on the AI feature (it serves the chat's proposal card)
        registry.add("openfilz.ai.active", () -> true);
    }

    @Test
    @DisplayName("inventory → proposal → REST apply moves the selected items and reports the rest")
    void proposeThenApplyThroughTheRestEndpoint() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        FolderResponse root = createFolder("reorg-" + suffix, null);
        UploadResponse first = upload("test1.txt", root.id());
        UploadResponse second = upload("test2.txt", root.id());
        FolderResponse old = createFolder("Old", root.id());
        UploadResponse nested = upload("test.txt", old.id());

        String inventory = callToolText("planReorganization", """
                {"folder":"%s"}""".formatted(root.id()));
        assertThat(inventory)
                .contains(first.id().toString(), second.id().toString(), nested.id().toString())
                .contains("/reorg-" + suffix + "/Old")
                .contains("HOW TO PROPOSE");

        String plan = """
                {"rootFolder":"%s","rationale":"Group the text files by year and archive the old folder",
                 "moves":[
                   {"document":"%s","target":"Text/2026"},
                   {"document":"%s","target":"Text/2026"},
                   {"document":"%s","target":"Archive"},
                   {"document":"%s","target":"Text"},
                   {"document":"%s","target":"Other"}]}"""
                .formatted(root.id(), first.id(), second.id(), old.id(), UUID.randomUUID(), first.id());
        String proposal = callToolText("proposeReorganizationPlan", JSON.writeValueAsString(Map.of("planJson", plan)));
        assertThat(proposal).contains("3 move(s) ready, 2 blocked").contains("NOTHING has moved");
        UUID planId = planIdOf(proposal);

        ReorganizationPlanView view = getPlan(planId, accessToken);
        assertThat(view.status()).isEqualTo("PROPOSED");
        assertThat(view.applicable()).isEqualTo(3);
        assertThat(view.blocked()).isEqualTo(2);
        assertThat(view.rationale()).contains("archive");
        assertThat(view.rootFolderPath()).isEqualTo("/reorg-" + suffix);
        assertThat(view.foldersToCreate()).containsExactlyInAnyOrder(
                "/reorg-" + suffix + "/Text", "/reorg-" + suffix + "/Text/2026", "/reorg-" + suffix + "/Archive");
        assertThat(view.items()).hasSize(5);
        assertThat(view.items().get(3).issue()).contains("visible to you");
        assertThat(view.items().get(4).issue()).contains("more than once");
        assertThat(view.items().get(0).targetExists()).isFalse();

        // Apply only the two files — the folder move stays unselected
        ReorganizationApplyResult result = webTestClient.post().uri(REORG + "/" + planId + "/apply")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReorganizationApplyRequest(List.of(first.id(), second.id())))
                .exchange().expectStatus().isOk()
                .expectBody(ReorganizationApplyResult.class).returnResult().getResponseBody();
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.moved()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.createdFolders()).containsExactly("/reorg-" + suffix + "/Text", "/reorg-" + suffix + "/Text/2026");
        assertThat(result.modifiedFolderIds()).contains(root.id().toString());

        // Verified through the API: the files now live under Text/2026, the folder did not move
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(first.id()))).contains("Text").contains("2026");
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(nested.id()))).contains("Old").doesNotContain("Archive");

        // A plan is applied once
        webTestClient.post().uri(REORG + "/" + planId + "/apply")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isEqualTo(409);
        webTestClient.post().uri(REORG + "/" + planId + "/discard")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isEqualTo(409);

        ReorganizationPlanView applied = getPlan(planId, accessToken);
        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.appliedAt()).isNotNull();
        assertThat(applied.results()).anyMatch(r -> first.id().equals(r.documentId()) && "MOVED".equals(r.outcome()));
        assertThat(applied.results()).anyMatch(r -> old.id().equals(r.documentId()) && "SKIPPED".equals(r.outcome()));

        assertThat(callToolText("getReorganizationPlan", """
                {"planId":"%s"}""".formatted(planId))).contains("(APPLIED)").contains("2 moved, 0 failed");
    }

    @Test
    @DisplayName("an agent applies its own proposal with the tool; a proposal can be discarded")
    void applyThroughTheToolAndDiscardThroughRest() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        FolderResponse root = createFolder("reorg-tool-" + suffix, null);
        FolderResponse old = createFolder("Old", root.id());
        UploadResponse nested = upload("test.txt", old.id());

        String plan = """
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Archive"}]}""".formatted(root.id(), old.id());
        UUID planId = planIdOf(callToolText("proposeReorganizationPlan", JSON.writeValueAsString(Map.of("planJson", plan))));

        String applied = callToolText("applyReorganizationPlan", """
                {"planId":"%s"}""".formatted(planId));
        assertThat(applied).contains("1 moved, 0 failed").contains("/reorg-tool-" + suffix + "/Archive");
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(nested.id()))).contains("Archive").contains("Old");

        // Folder moves to the root level are supported too
        String toRoot = """
                {"rootFolder":"%s","moves":[{"document":"%s","target":""}]}""".formatted(root.id(), old.id());
        UUID rootPlan = planIdOf(callToolText("proposeReorganizationPlan", JSON.writeValueAsString(Map.of("planJson", toRoot))));
        assertThat(callToolText("applyReorganizationPlan", """
                {"planId":"%s"}""".formatted(rootPlan))).contains("1 moved, 0 failed");
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(nested.id()))).doesNotContain("Archive");

        String again = """
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Elsewhere"}]}""".formatted(root.id(), nested.id());
        UUID discarded = planIdOf(callToolText("proposeReorganizationPlan", JSON.writeValueAsString(Map.of("planJson", again))));
        ReorganizationPlanView view = webTestClient.post().uri(REORG + "/" + discarded + "/discard")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(ReorganizationPlanView.class).returnResult().getResponseBody();
        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo("DISCARDED");
        assertThat(callToolText("applyReorganizationPlan", """
                {"planId":"%s"}""".formatted(discarded))).contains("discarded");
    }

    @Test
    @DisplayName("validation blocks self-moves, name clashes and no-op moves without persisting")
    void blockedMovesAreExplained() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        FolderResponse root = createFolder("reorg-blocked-" + suffix, null);
        FolderResponse a = createFolder("A", root.id());
        createFolder("B", a.id());
        UploadResponse file = upload("test.txt", root.id());
        FolderResponse dup = createFolder("Dup", root.id());
        upload("test.txt", dup.id());

        String plan = """
                {"rootFolder":"%s","moves":[
                   {"document":"%s","target":"A/B"},
                   {"document":"%s","target":"Dup"},
                   {"document":"%s","target":""}]}""".formatted(root.id(), a.id(), file.id(), file.id());
        String answer = callToolText("proposeReorganizationPlan", JSON.writeValueAsString(Map.of("planJson", plan)));
        assertThat(answer)
                .contains("Nothing in this plan can be applied")
                .contains("into itself")
                .contains("already exists")
                .contains("more than once");
        assertThat(answer).doesNotContainPattern(PLAN_ID);
    }

    @Test
    @DisplayName("a plan belongs to the user who proposed it")
    void plansAreOwnedByTheirProposer() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        FolderResponse root = createFolder("reorg-owner-" + suffix, null);
        UploadResponse file = upload("test.txt", root.id());
        String plan = """
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Mine"}]}""".formatted(root.id(), file.id());
        UUID planId = planIdOf(callToolText("proposeReorganizationPlan", JSON.writeValueAsString(Map.of("planJson", plan))));

        String other = getAccessToken("contributor-user");
        webTestClient.get().uri(REORG + "/" + planId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                .exchange().expectStatus().isNotFound();
        webTestClient.post().uri(REORG + "/" + planId + "/apply")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("a READER may inventory but neither propose nor apply")
    void readerCannotProposeOrApply() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        FolderResponse root = createFolder("reorg-reader-" + suffix, null);
        UploadResponse file = upload("test.txt", root.id());
        String plan = """
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Mine"}]}""".formatted(root.id(), file.id());
        UUID planId = planIdOf(callToolText("proposeReorganizationPlan", JSON.writeValueAsString(Map.of("planJson", plan))));

        String admin = accessToken;
        accessToken = getAccessToken("reader-user");
        try {
            assertThat(callToolText("planReorganization", """
                    {"folder":"%s"}""".formatted(root.id()))).contains(file.id().toString());
            assertThat(callToolText("proposeReorganizationPlan", JSON.writeValueAsString(Map.of("planJson", plan))))
                    .contains("Not permitted");
            webTestClient.post().uri(REORG + "/" + planId + "/apply")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .exchange().expectStatus().isForbidden();
        } finally {
            accessToken = admin;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static UUID planIdOf(String answer) {
        Matcher matcher = PLAN_ID.matcher(answer);
        assertThat(matcher.find()).as("a proposal answer names the plan id; was: %s", answer).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private ReorganizationPlanView getPlan(UUID planId, String token) {
        ReorganizationPlanView view = webTestClient.get().uri(REORG + "/" + planId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(ReorganizationPlanView.class).returnResult().getResponseBody();
        assertThat(view).isNotNull();
        return view;
    }

    private FolderResponse createFolder(String name, UUID parentId) {
        FolderResponse folder = webTestClient.post().uri(RestApiVersion.API_PREFIX + "/folders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateFolderRequest(name, parentId))
                .exchange().expectStatus().isCreated()
                .expectBody(FolderResponse.class).returnResult().getResponseBody();
        assertThat(folder).isNotNull();
        return folder;
    }

    private UploadResponse upload(String fixture, UUID parentId) {
        MultipartBodyBuilder builder = newFileBuilder(fixture);
        if (parentId != null) {
            builder.part("parentFolderId", parentId.toString());
        }
        UploadResponse response = newFile(builder, accessToken);
        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        return response;
    }
}
