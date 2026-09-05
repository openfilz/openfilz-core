package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.ReorganizationApplyResult;
import org.openfilz.dms.dto.response.ReorganizationPlanView;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.http.codec.json.JacksonJsonEncoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Reorganisation by kind, end to end and without a model: a folder of invoices and reports is
 * split into one sub-folder per kind through the REST endpoint, the plan is an ordinary stored
 * proposal, applying it moves the files, and a folder of one kind is left alone.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(AiTestConfig.class)
class ReorganizationByKindIT extends TestContainersBaseConfig {

    private static final String REORG = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/reorganization";
    private static final String DOCUMENTS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;

    ReorganizationByKindIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.insights.active", () -> true);
        registry.add("openfilz.ai.reorganization.split-min-files", () -> 4);
        registry.add("openfilz.ai.reorganization.split-min-group", () -> 2);
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("spring.ai.model.embedding", () -> "none");
        registry.add("spring.ai.model.image", () -> "none");
        registry.add("spring.ai.model.moderation", () -> "none");
        registry.add("spring.ai.model.audio.speech", () -> "none");
        registry.add("spring.ai.model.audio.transcription", () -> "none");
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");
    }

    @Test
    @Order(1)
    @DisplayName("a folder of invoices and reports is split into Invoices and Reports; applying the plan moves the files")
    void mixedFolderIsSplitByKind() {
        FolderResponse scope = createFolder("Scope-" + UUID.randomUUID().toString().substring(0, 8), null);
        FolderResponse mixed = createFolder("Archive", scope.id());
        List<UUID> invoices = new ArrayList<>();
        List<UUID> reports = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            invoices.add(upload("invoice-" + i + ".txt", "Invoice F-2026-07" + i + " from Globex, amount due.", mixed.id()).id());
            reports.add(upload("report-" + i + ".txt", "Monthly report " + i + " of Globex, figures and outlook.", mixed.id()).id());
        }
        // One odd file out: too few of its kind for a folder — it stays (the mock labels it "other")
        UUID odd = upload("notes.txt", "Miscellaneous notes of Globex.", mixed.id()).id();
        for (UUID id : invoices) awaitTier2(id);
        for (UUID id : reports) awaitTier2(id);
        awaitTier2(odd);

        ReorganizationPlanView plan = getWebTestClient().post().uri(REORG + "/by-kind")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"rootFolderId\":\"" + scope.id() + "\"}"))
                .exchange().expectStatus().isOk().expectBody(ReorganizationPlanView.class).returnResult().getResponseBody();
        assertThat(plan).isNotNull();
        assertThat(plan.id()).as("a stored proposal: " + plan).isNotNull();
        assertThat(plan.status()).isEqualTo("PROPOSED");
        assertThat(plan.foldersToCreate()).containsExactlyInAnyOrder("/" + scope.name() + "/Archive/Invoices", "/" + scope.name() + "/Archive/Reports");
        assertThat(plan.items()).hasSize(8);
        assertThat(plan.applicable()).isEqualTo(8);
        assertThat(plan.rationale()).contains("Split 1 folder").contains("named in en");
        assertThat(plan.items()).allSatisfy(item -> assertThat(item.targetPath())
                .isIn("/" + scope.name() + "/Archive/Invoices", "/" + scope.name() + "/Archive/Reports"));
        assertThat(plan.items()).filteredOn(item -> invoices.contains(item.documentId()))
                .allSatisfy(item -> assertThat(item.targetPath()).endsWith("/Invoices"));
        assertThat(plan.items()).filteredOn(item -> reports.contains(item.documentId()))
                .allSatisfy(item -> assertThat(item.targetPath()).endsWith("/Reports"));

        // The plan is an ordinary proposal: readable, then applied
        ReorganizationPlanView stored = getWebTestClient().get().uri(REORG + "/" + plan.id())
                .exchange().expectStatus().isOk().expectBody(ReorganizationPlanView.class).returnResult().getResponseBody();
        assertThat(stored).isNotNull();
        assertThat(stored.items()).hasSize(8);

        ReorganizationApplyResult applied = getWebTestClient().post().uri(REORG + "/" + plan.id() + "/apply")
                .exchange().expectStatus().isOk().expectBody(ReorganizationApplyResult.class).returnResult().getResponseBody();
        assertThat(applied).isNotNull();
        assertThat(applied.status()).as(applied.toString()).isEqualTo("APPLIED");

        UUID invoicesFolder = info(invoices.getFirst()).parentId();
        UUID reportsFolder = info(reports.getFirst()).parentId();
        assertThat(invoicesFolder).isNotEqualTo(mixed.id()).isNotEqualTo(reportsFolder);
        assertThat(info(invoicesFolder).name()).isEqualTo("Invoices");
        assertThat(info(invoicesFolder).parentId()).isEqualTo(mixed.id());
        assertThat(info(reportsFolder).name()).isEqualTo("Reports");
        for (UUID id : invoices) {
            assertThat(info(id).parentId()).as("invoice " + id).isEqualTo(invoicesFolder);
        }
        for (UUID id : reports) {
            assertThat(info(id).parentId()).as("report " + id).isEqualTo(reportsFolder);
        }
        assertThat(info(odd).parentId()).as("the odd one out stays").isEqualTo(mixed.id());

        // Split once: the scope is now tidy, a second request has nothing to propose
        ReorganizationPlanView again = getWebTestClient().post().uri(REORG + "/by-kind")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"rootFolderId\":\"" + scope.id() + "\"}"))
                .exchange().expectStatus().isOk().expectBody(ReorganizationPlanView.class).returnResult().getResponseBody();
        assertThat(again).isNotNull();
        assertThat(again.id()).as("nothing left to split: " + again).isNull();
        assertThat(again.items()).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("an existing folder that denotes the kind is reused, and the new one is named in the library's language")
    void existingKindFolderIsReusedAndLanguageFollowsTheLibrary() {
        // A French library: "Contrats" exists under the mixed folder, so invoices go there... no —
        // contracts do; invoices get a new "Factures", named in French like the folders around it
        FolderResponse scope = createFolder("Scope-" + UUID.randomUUID().toString().substring(0, 8), null);
        FolderResponse clients = createFolder("Clients", scope.id());
        FolderResponse acme = createFolder("ACME", clients.id());
        FolderResponse contrats = createFolder("Contrats", acme.id());
        createFolder("Courriers", scope.id());
        List<UUID> invoices = new ArrayList<>();
        List<UUID> reports = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            invoices.add(upload("facture-" + i + ".txt", "Invoice F-2026-08" + i + " from ACME, amount due.", acme.id()).id());
            reports.add(upload("rapport-" + i + ".txt", "Monthly report " + i + " of ACME, figures.", acme.id()).id());
        }
        for (UUID id : invoices) awaitTier2(id);
        for (UUID id : reports) awaitTier2(id);

        ReorganizationPlanView plan = getWebTestClient().post().uri(REORG + "/by-kind")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"rootFolderId\":\"" + scope.id() + "\"}"))
                .exchange().expectStatus().isOk().expectBody(ReorganizationPlanView.class).returnResult().getResponseBody();
        assertThat(plan).isNotNull();
        assertThat(plan.id()).as(String.valueOf(plan)).isNotNull();
        String acmePath = "/" + scope.name() + "/Clients/ACME";
        assertThat(plan.foldersToCreate()).containsExactlyInAnyOrder(acmePath + "/Factures", acmePath + "/Rapports");
        assertThat(plan.rationale()).contains("named in fr");
        assertThat(contrats.id()).isNotNull();
        assertThat(plan.items()).hasSize(6);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void awaitTier2(UUID documentId) {
        for (int attempt = 0; attempt < 240; attempt++) {
            var result = getWebTestClient().get().uri(DOCUMENTS + "/" + documentId + "/insights")
                    .exchange().returnResult(DocumentInsightView.class);
            if (result.getStatus().is2xxSuccessful()) {
                DocumentInsightView view = result.getResponseBody().blockFirst();
                if (view != null && view.tier() == 2 && "DONE".equals(view.status())) {
                    return;
                }
            }
            sleep(250);
        }
        throw new AssertionError("no tier-2 insight for " + documentId);
    }

    private UploadResponse upload(String name, String content, UUID parentId) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        }).contentType(MediaType.TEXT_PLAIN);
        if (parentId != null) {
            builder.part("parentFolderId", parentId.toString());
        }
        return getWebTestClient().post().uri(DOCUMENTS + "/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated().expectBody(UploadResponse.class).returnResult().getResponseBody();
    }

    private FolderResponse createFolder(String name, UUID parentId) {
        return getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new CreateFolderRequest(name, parentId)))
                .exchange().expectStatus().isCreated().expectBody(FolderResponse.class).returnResult().getResponseBody();
    }

    private DocumentInfo info(UUID documentId) {
        return getWebTestClient().get().uri(DOCUMENTS + "/" + documentId + "/info")
                .exchange().expectStatus().isOk().expectBody(DocumentInfo.class).returnResult().getResponseBody();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
