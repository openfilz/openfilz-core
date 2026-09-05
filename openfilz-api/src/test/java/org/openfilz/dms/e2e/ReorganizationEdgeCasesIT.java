package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.RenameRequest;
import org.openfilz.dms.dto.request.ReorganizationApplyRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.ReorganizationApplyResult;
import org.openfilz.dms.dto.response.ReorganizationPlanView;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
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
 * The edges of the AI reorganisation loop, next to the happy paths of {@link OrganizeAiToolsIT}:
 * what the inventory reports when it hits its depth/entry limits or has nothing to list, how a
 * malformed or impossible plan is refused, how the tools guard their arguments, and what happens
 * when the library changes between a proposal and its application.
 * <p>
 * Everything goes through the real front-ends — the MCP tools and the REST endpoint the chat
 * proposal card calls — and is asserted through the public API only.
 * <p>
 * The context configuration is deliberately identical to {@link OrganizeAiToolsIT} so both suites
 * share one Spring context.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
class ReorganizationEdgeCasesIT extends AbstractMcpIT {

    private static final String REORG = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/reorganization";
    private static final Pattern PLAN_ID = Pattern.compile("Plan ([0-9a-f-]{36})");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    ReorganizationEdgeCasesIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
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
    @DisplayName("the inventory reports the root level, its depth and entry limits, and an empty folder")
    void inventoryLimitsAreReported() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-inv-" + suffix, null);
        FolderResponse level1 = createFolder("Level1", root.id());
        createFolder("Level2", level1.id());
        upload("test1.txt", root.id());
        upload("test2.txt", root.id());

        // The root level needs no folder argument at all
        assertThat(callToolText("planReorganization", "{}"))
                .contains("(root level)")
                .contains("HOW TO PROPOSE");

        // Only the first level is walked; deeper folders are listed but not expanded
        String shallow = callToolText("planReorganization", """
                {"folder":"%s","maxDepth":1}""".formatted(root.id()));
        assertThat(shallow)
                .contains("/reorg-inv-" + suffix + "/Level1")
                .contains("[not expanded: depth limit]")
                .doesNotContain("/Level1/Level2");

        // The whole subtree, so the nested folder shows up
        assertThat(callToolText("planReorganization", """
                {"folder":"%s"}""".formatted(root.id()))).contains("/reorg-inv-" + suffix + "/Level1/Level2");

        // A single entry is listed, the rest is announced as truncated
        assertThat(callToolText("planReorganization", """
                {"folder":"%s","maxItems":1}""".formatted(root.id())))
                .contains("TRUNCATED at 1 entries");

        // An empty folder says so rather than listing nothing
        FolderResponse empty = createFolder("Empty", root.id());
        assertThat(callToolText("planReorganization", """
                {"folder":"%s"}""".formatted(empty.id()))).contains("No files.");
    }

    @Test
    @DisplayName("the inventory describes each file with its size, date and metadata")
    void inventoryDescribesFiles() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-desc-" + suffix, null);
        MultipartBodyBuilder builder = newFileBuilder("test1.txt");
        builder.part("parentFolderId", root.id().toString());
        builder.part("metadata", Map.of("category", "invoice-" + suffix));
        UploadResponse file = newFile(builder, accessToken);
        assertThat(file).isNotNull();

        assertThat(callToolText("planReorganization", """
                {"folder":"%s"}""".formatted(root.id())))
                .contains(file.id().toString())
                .contains("| txt |")
                .contains("category=invoice-" + suffix)
                // Phase D header: the rows carry the insights and the audit activity too
                .contains("Files (id | path | ext | size | modified by")
                .contains("last ").contains("action");
    }

    @Test
    @DisplayName("planReorganization refuses a name that is not a visible folder")
    void planReorganizationNeedsAFolder() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-nofolder-" + suffix, null);
        UploadResponse file = upload("test.txt", root.id());

        assertThat(callToolText("planReorganization", """
                {"folder":"no-such-folder-%s"}""".formatted(suffix)))
                .contains("is visible to you")
                .contains("queryDocuments");

        // A file is not a folder either
        assertThat(callToolText("planReorganization", """
                {"folder":"%s"}""".formatted(file.id()))).contains("is visible to you");
    }

    @Test
    @DisplayName("a plan that is not the documented JSON contract is refused with an explanation")
    void malformedPlansAreRefused() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-json-" + suffix, null);
        UploadResponse file = upload("test.txt", root.id());

        assertThat(propose("this is not json")).contains("not valid JSON");
        assertThat(propose("{\"rationale\":\"no moves key\"}")).contains("must be a JSON object with a 'moves' array");
        assertThat(propose("{\"moves\":[]}")).contains("The plan has no moves");

        // A model that wraps its JSON in a Markdown code fence is still understood
        String fenced = """
                ```json
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Fenced"}]}
                ```""".formatted(root.id(), file.id());
        String proposal = propose(fenced);
        assertThat(proposal).contains("1 move(s) ready");
        assertThat(planIdOf(proposal)).isNotNull();

        // The root folder of a plan must be a folder
        assertThat(propose("""
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Anywhere"}]}"""
                .formatted(file.id(), file.id()))).contains("is visible to you");

        // A target that climbs out of the root is not a path
        assertThat(propose("""
                {"rootFolder":"%s","moves":[{"document":"%s","target":"../escape"}]}"""
                .formatted(root.id(), file.id())))
                .contains("Nothing in this plan can be applied")
                .contains("Invalid target path");
    }

    @Test
    @DisplayName("a very long plan is summarised rather than dumped in full")
    void longPlansAreTruncatedInTheAnswer() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-long-" + suffix, null);
        StringBuilder moves = new StringBuilder();
        for (int i = 0; i < 61; i++) {
            if (i > 0) moves.append(',');
            moves.append("""
                    {"document":"%s","target":"Bucket%d"}""".formatted(UUID.randomUUID(), i));
        }

        String answer = propose("""
                {"rootFolder":"%s","moves":[%s]}""".formatted(root.id(), moves));
        assertThat(answer)
                .contains("Nothing in this plan can be applied")
                .contains("1 more");
    }

    @Test
    @DisplayName("the tools validate their own arguments before touching a plan")
    void toolArgumentsAreValidated() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-args-" + suffix, null);
        UploadResponse file = upload("test.txt", root.id());
        UUID planId = planIdOf(propose("""
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Sorted"}]}"""
                .formatted(root.id(), file.id())));

        assertThat(callToolText("applyReorganizationPlan", """
                {"planId":"not-an-id"}""")).contains("planId must be the id of a proposed plan");
        assertThat(callToolText("applyReorganizationPlan", """
                {"planId":"%s","documentIds":"not-an-id"}""".formatted(planId)))
                .contains("documentIds must be document ids");
        assertThat(callToolText("applyReorganizationPlan", """
                {"planId":"%s","documentIds":"%s"}""".formatted(planId, UUID.randomUUID())))
                .contains("No applicable item was selected");
        assertThat(callToolText("getReorganizationPlan", """
                {"planId":"not-an-id"}""")).contains("planId must be the id of a plan");

        // The plan is still applicable: the refusals above changed nothing
        assertThat(callToolText("applyReorganizationPlan", """
                {"planId":"%s"}""".formatted(planId))).contains("1 moved, 0 failed");
    }

    @Test
    @DisplayName("a plan of another user is invisible to the tools and to REST")
    void plansOfOtherUsersAreNotFound() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-foreign-" + suffix, null);
        UploadResponse file = upload("test.txt", root.id());
        UUID planId = planIdOf(propose("""
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Mine"}]}"""
                .formatted(root.id(), file.id())));

        String admin = accessToken;
        accessToken = getAccessToken("contributor-user");
        try {
            assertThat(callToolText("getReorganizationPlan", """
                    {"planId":"%s"}""".formatted(planId))).contains("Plan not found");
        } finally {
            accessToken = admin;
        }

        // An id that never existed answers the same way
        webTestClient.get().uri(REORG + "/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("discarding a plan twice is idempotent and an applied plan cannot be discarded")
    void discardIsIdempotent() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-discard-" + suffix, null);
        UploadResponse file = upload("test.txt", root.id());
        UUID planId = planIdOf(propose("""
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Nowhere"}]}"""
                .formatted(root.id(), file.id())));

        assertThat(discard(planId).status()).isEqualTo("DISCARDED");
        assertThat(discard(planId).status()).isEqualTo("DISCARDED");

        // Nothing moved: the file is still where it was
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(file.id()))).doesNotContain("Nowhere");
    }

    @Test
    @DisplayName("createFolders of a plan are created even where nothing moves into them")
    void extraFoldersOfAPlanAreCreated() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-create-" + suffix, null);
        UploadResponse file = upload("test.txt", root.id());
        UUID planId = planIdOf(propose("""
                {"rootFolder":"%s","rationale":"Prepare the shelves",
                 "createFolders":["Reports/2026","Reports/2025"],
                 "moves":[{"document":"%s","target":"Inbox"}]}"""
                .formatted(root.id(), file.id())));

        ReorganizationPlanView view = getPlan(planId);
        assertThat(view.foldersToCreate()).contains(
                "/reorg-create-" + suffix + "/Reports",
                "/reorg-create-" + suffix + "/Reports/2026",
                "/reorg-create-" + suffix + "/Reports/2025",
                "/reorg-create-" + suffix + "/Inbox");

        // An empty selection means "every applicable item"
        ReorganizationApplyResult result = apply(planId, new ReorganizationApplyRequest(List.of()));
        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.moved()).isEqualTo(1);
        assertThat(result.createdFolders()).contains(
                "/reorg-create-" + suffix + "/Reports",
                "/reorg-create-" + suffix + "/Reports/2026",
                "/reorg-create-" + suffix + "/Reports/2025",
                "/reorg-create-" + suffix + "/Inbox");

        // The parents are created before their children, so the whole tree really exists
        assertThat(callToolText("planReorganization", """
                {"folder":"%s"}""".formatted(root.id())))
                .contains("/reorg-create-" + suffix + "/Reports/2026")
                .contains("/reorg-create-" + suffix + "/Reports/2025");
    }

    @Test
    @DisplayName("documents can be named instead of identified, and an existing folder matches case-insensitively")
    void documentsAndFoldersAreResolvedByName() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-name-" + suffix, null);
        createFolder("Text", root.id());
        UploadResponse file = upload("test.txt", root.id());
        renameFile(file.id(), "unique-" + suffix + ".txt");

        UUID planId = planIdOf(propose("""
                {"rootFolder":"reorg-name-%s","moves":[{"document":"unique-%s.txt","target":"text"}]}"""
                .formatted(suffix, suffix)));

        ReorganizationPlanView view = getPlan(planId);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().getFirst().documentId()).isEqualTo(file.id());
        assertThat(view.items().getFirst().targetExists()).isTrue();
        // The path echoes the spelling the model used; it resolved to the existing "Text" folder
        assertThat(view.items().getFirst().targetPath()).isEqualToIgnoringCase("/reorg-name-" + suffix + "/Text");
        assertThat(view.foldersToCreate()).isEmpty();

        assertThat(apply(planId, null).moved()).isEqualTo(1);
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(file.id()))).contains("Text");
    }

    @Test
    @DisplayName("a plan is re-validated on apply, so a clash created meanwhile blocks the move")
    void plansAreRevalidatedOnApply() {
        String suffix = suffix();
        FolderResponse root = createFolder("reorg-stale-" + suffix, null);
        UploadResponse file = upload("test.txt", root.id());
        UUID planId = planIdOf(propose("""
                {"rootFolder":"%s","moves":[{"document":"%s","target":"Target"}]}"""
                .formatted(root.id(), file.id())));
        assertThat(getPlan(planId).applicable()).isEqualTo(1);

        // Meanwhile the user creates the target folder by hand and puts a file of the same name in it
        FolderResponse target = createFolder("Target", root.id());
        upload("test.txt", target.id());

        webTestClient.post().uri(REORG + "/" + planId + "/apply")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isBadRequest();

        // The plan is untouched and the document never moved
        assertThat(getPlan(planId).status()).isEqualTo("PROPOSED");
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(file.id()))).doesNotContain("Target");
    }

    // ---------------------------------------------------------------- helpers

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String propose(String planJson) {
        return callToolText("proposeReorganizationPlan", JSON.writeValueAsString(Map.of("planJson", planJson)));
    }

    private static UUID planIdOf(String answer) {
        Matcher matcher = PLAN_ID.matcher(answer);
        assertThat(matcher.find()).as("a proposal answer names the plan id; was: %s", answer).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private ReorganizationPlanView getPlan(UUID planId) {
        ReorganizationPlanView view = webTestClient.get().uri(REORG + "/" + planId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(ReorganizationPlanView.class).returnResult().getResponseBody();
        assertThat(view).isNotNull();
        return view;
    }

    private ReorganizationApplyResult apply(UUID planId, ReorganizationApplyRequest request) {
        WebTestClient.RequestHeadersSpec<?> spec = request == null
                ? webTestClient.post().uri(REORG + "/" + planId + "/apply")
                : webTestClient.post().uri(REORG + "/" + planId + "/apply")
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(request);
        ReorganizationApplyResult result = spec
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(ReorganizationApplyResult.class).returnResult().getResponseBody();
        assertThat(result).isNotNull();
        return result;
    }

    private ReorganizationPlanView discard(UUID planId) {
        ReorganizationPlanView view = webTestClient.post().uri(REORG + "/" + planId + "/discard")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
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

    private void renameFile(UUID fileId, String newName) {
        webTestClient.put().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FILES + "/" + fileId + "/rename")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RenameRequest(newName))
                .exchange().expectStatus().isOk();
    }
}
