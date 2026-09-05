package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.dto.response.InsightBackfillStatus;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Tier-2 document insights against the mocked chat model of {@link AiTestConfig}: an upload is
 * enriched asynchronously (category from the closed list, summary, entities), a model answer
 * that is not the contract ends as FAILED, the backfill re-enriches with force, the category
 * filters {@code queryDocuments}, and the settings advertise the feature.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
class DocumentInsightsTier2IT extends TestContainersBaseConfig {

    private static final String INSIGHTS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/insights";

    @Autowired
    private DocumentAiTools documentAiTools;

    @Autowired
    private org.openfilz.dms.service.ai.ReorganizationPlanService planService;

    DocumentInsightsTier2IT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.insights.active", () -> true);
        registry.add("openfilz.ai.insights.concurrency", () -> 2);
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
    @DisplayName("an upload is enriched: category from the closed list, summary, entities; visible in the tool and the filter")
    void uploadIsEnriched() {
        UploadResponse uploaded = uploadDocument(textFile("insights-" + UUID.randomUUID() + ".txt",
                "Quarterly report for ACME: revenue grew 12% and the outlook is stable."));

        DocumentInsightView view = awaitInsights(uploaded.id(), v -> "DONE".equals(v.status()) && v.tier() == 2);

        assertThat(view.category()).isEqualTo("report");
        assertThat(view.summary()).contains("short test summary");
        assertThat(view.keywords()).contains("test", "report");
        assertThat(view.entities()).containsEntry("client", "ACME");
        assertThat(view.model()).isNotBlank();
        assertThat(view.promptVersion()).isEqualTo(1);

        assertThat(documentAiTools.getMetadata(uploaded.id().toString()))
                .contains("Insights").contains("\"category\":\"report\"");
        assertThat(documentAiTools.queryDocuments("all", uploaded.name(), "FILE", null, null, 10, null, "report"))
                .contains(uploaded.name());
        assertThat(documentAiTools.queryDocuments("all", uploaded.name(), "FILE", null, null, 10, null, "invoice"))
                .contains("No documents found");

        Settings settings = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                .exchange().expectStatus().isOk().expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        assertThat(settings.aiInsightsActive()).isTrue();
    }

    @Test
    @DisplayName("a model answer that is not the contract ends as FAILED with the reason, never a half row")
    void badModelAnswerIsFailed() {
        UploadResponse uploaded = uploadDocument(textFile("malformed-" + UUID.randomUUID() + ".txt",
                "Some content the mocked model refuses to label."));

        DocumentInsightView view = awaitInsights(uploaded.id(), v -> "FAILED".equals(v.status()));

        assertThat(view.category()).isNull();
        assertThat(view.error()).contains("model answer rejected");
        assertThat(view.tier()).isEqualTo(1);
    }

    @Test
    @DisplayName("a forced backfill re-enriches existing documents and reports its progress")
    void backfillReEnriches() {
        UploadResponse first = uploadDocument(textFile("backfill-a-" + UUID.randomUUID() + ".txt", "Contract between ACME and Globex."));
        UploadResponse second = uploadDocument(textFile("backfill-b-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0042."));
        awaitInsights(first.id(), v -> "DONE".equals(v.status()) && v.tier() == 2);
        awaitInsights(second.id(), v -> "DONE".equals(v.status()) && v.tier() == 2);

        InsightBackfillStatus started = getWebTestClient().post().uri(INSIGHTS + "/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"force\":true}"))
                .exchange().expectStatus().isOk()
                .expectBody(InsightBackfillStatus.class).returnResult().getResponseBody();
        assertThat(started).isNotNull();
        assertThat(started.force()).isTrue();

        InsightBackfillStatus finished = null;
        for (int attempt = 0; attempt < 120; attempt++) {
            finished = getWebTestClient().get().uri(INSIGHTS + "/backfill/" + started.jobId())
                    .exchange().expectStatus().isOk()
                    .expectBody(InsightBackfillStatus.class).returnResult().getResponseBody();
            if (finished != null && "DONE".equals(finished.status())) break;
            sleep();
        }
        assertThat(finished).isNotNull();
        assertThat(finished.status()).isEqualTo("DONE");
        assertThat(finished.total()).isGreaterThanOrEqualTo(2);
        assertThat(finished.done() + finished.failed() + finished.skipped()).isEqualTo(finished.total());
        assertThat(awaitInsights(first.id(), v -> "DONE".equals(v.status())).category()).isEqualTo("report");

        getWebTestClient().get().uri(INSIGHTS + "/backfill/" + UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("the reorganisation inventory carries the insights and the audit activity, summaries only in full detail")
    void inventoryCarriesInsightsAndActivity() {
        UploadResponse uploaded = uploadDocument(textFile("inventory-" + UUID.randomUUID() + ".txt",
                "Board meeting report: decisions on the ACME account."));
        awaitInsights(uploaded.id(), v -> "DONE".equals(v.status()) && v.tier() == 2);
        // The core access policy is permit-all, so any caller identity sees the whole library
        var caller = new org.openfilz.dms.service.ai.ReorganizationPlanService.Caller("inventory-test@example.com", null);

        String full = planService.inventory(null, 1, 1000, "full", caller);
        String row = full.lines().filter(l -> l.contains(uploaded.id().toString())).findFirst().orElse("");
        assertThat(row).as(full).contains("cat report").contains("\"A short test summary")
                .contains("last ").contains("action").contains("user");
        assertThat(full).contains("Categories present").contains("report");

        String compact = planService.inventory(null, 1, 1000, "compact", caller);
        String compactRow = compact.lines().filter(l -> l.contains(uploaded.id().toString())).findFirst().orElse("");
        assertThat(compactRow).as(compact).contains("cat report").doesNotContain("A short test summary").contains("kw ");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static MultipartBodyBuilder textFile(String name, String content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        }).contentType(MediaType.TEXT_PLAIN);
        return builder;
    }

    private DocumentInsightView awaitInsights(UUID documentId, Predicate<DocumentInsightView> ready) {
        String uri = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS + "/" + documentId + "/insights";
        DocumentInsightView last = null;
        for (int attempt = 0; attempt < 120; attempt++) {
            var result = getWebTestClient().get().uri(uri).exchange().returnResult(DocumentInsightView.class);
            if (result.getStatus().is2xxSuccessful()) {
                last = result.getResponseBody().blockFirst();
                if (last != null && ready.test(last)) {
                    return last;
                }
            }
            sleep();
        }
        throw new AssertionError("insights of " + documentId + " never reached the expected state; last: " + last);
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
