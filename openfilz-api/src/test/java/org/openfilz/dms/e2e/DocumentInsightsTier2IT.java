package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.dto.response.InsightBackfillStatus;
import org.openfilz.dms.dto.response.InsightFacets;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Tier-2 document insights against the mocked chat model of {@link AiTestConfig}: an upload is
 * enriched asynchronously (category from the closed list, summary, entities), a model answer
 * that is not the contract ends as FAILED, the backfill re-enriches with force, the category
 * filters {@code queryDocuments} and the GraphQL search (DB path), the facets endpoint counts it,
 * and the settings advertise the feature.
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
        // A deployment label on top of the built-in ones (bracket key: the hyphen survives the binding)
        registry.add("openfilz.ai.insights.category-labels[id-document].de", () -> "Personalausweis");
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
        assertThat(view.promptVersion()).isEqualTo(org.openfilz.dms.service.insight.AiDocumentInsightService.PROMPT_VERSION);

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
    @DisplayName("the settings name every kind in the Accept-Language language, English when it is not supported, a deployment label winning")
    void settingsLabelTheKindsInTheCallersLanguage() {
        Map<String, String> french = categoryLabels("fr-FR,fr;q=0.9,en;q=0.8");
        assertThat(french.keySet()).containsExactlyElementsOf(settingsFor(null).aiInsightsCategories());
        assertThat(french).containsEntry("invoice", "Facture").containsEntry("id-document", "Pièce d’identité")
                .containsEntry("other", "Autre");

        assertThat(categoryLabels("de-CH, de;q=0.9")).containsEntry("id-document", "Personalausweis").containsEntry("invoice", "Rechnung");
        assertThat(categoryLabels("ja-JP")).containsEntry("invoice", "Invoice").containsEntry("id-document", "ID document");
        assertThat(settingsFor(null).aiInsightsCategoryLabels()).containsEntry("invoice", "Invoice");
    }

    private Map<String, String> categoryLabels(String acceptLanguage) {
        return settingsFor(acceptLanguage).aiInsightsCategoryLabels();
    }

    private Settings settingsFor(String acceptLanguage) {
        Settings settings = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                .headers(headers -> {
                    if (acceptLanguage != null) {
                        headers.set(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
                    }
                })
                .exchange().expectStatus().isOk().expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        return settings;
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

    @Test
    @DisplayName("the category and language filter the GraphQL search on the DB path, and the facets endpoint counts them")
    void categoryFacetsAndSearchFilters() {
        String name = "facet-" + UUID.randomUUID() + ".txt";
        UploadResponse uploaded = uploadDocument(textFile(name, "Invoice F-2026-0777 from Globex, total due 1 200 EUR."));
        awaitInsights(uploaded.id(), v -> "DONE".equals(v.status()) && v.tier() == 2 && "invoice".equals(v.category()));

        HttpGraphQlClient client = newGraphQlClient();
        String id = uploaded.id().toString();
        assertThat(searchIds(client, name, "category", "invoice")).contains(id);
        // several keys, spelled loosely: normalised like the stored value
        assertThat(searchIds(client, name, "category", "Invoice, quote")).contains(id);
        assertThat(searchIds(client, name, "category", "contract")).isEmpty();
        assertThat(searchIds(client, name, "language", "en")).contains(id);
        assertThat(searchIds(client, name, "language", "fr")).isEmpty();
        // both facets at once
        assertThat(searchIds(client, name, List.of(Map.of("field", "category", "value", "invoice"),
                Map.of("field", "language", "value", "en")))).contains(id);
        assertThat(searchIds(client, name, List.of(Map.of("field", "category", "value", "invoice"),
                Map.of("field", "language", "value", "de")))).isEmpty();

        InsightFacets facets = getWebTestClient().get().uri(INSIGHTS + "/facets")
                .exchange().expectStatus().isOk()
                .expectBody(InsightFacets.class).returnResult().getResponseBody();
        assertThat(facets).isNotNull();
        assertThat(facets.categories()).as(facets.toString())
                .anyMatch(f -> "invoice".equals(f.key()) && f.count() >= 1);
        assertThat(facets.languages()).as(facets.toString())
                .anyMatch(f -> "en".equals(f.key()) && f.count() >= 1);
        // largest first
        assertThat(facets.categories()).isSortedAccordingTo((a, b) -> Long.compare(b.count(), a.count()));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private List<String> searchIds(HttpGraphQlClient client, String query, String field, String value) {
        return searchIds(client, query, List.of(Map.of("field", field, "value", value)));
    }

    /** The ids {@code searchDocuments} answers for a name query and the given {@code FilterInput}s. */
    @SuppressWarnings("unchecked")
    private List<String> searchIds(HttpGraphQlClient client, String query, List<Map<String, String>> filters) {
        StringBuilder filterList = new StringBuilder();
        for (Map<String, String> filter : filters) {
            if (!filterList.isEmpty()) filterList.append(", ");
            filterList.append("{ field: \"").append(filter.get("field")).append("\", value: \"").append(filter.get("value")).append("\" }");
        }
        String document = """
                query {
                  searchDocuments(query: "%s", filters: [%s], page: 1, size: 20) {
                    totalHits
                    documents { id name }
                  }
                }""".formatted(query, filterList);
        ClientGraphQlResponse response = client.document(document).execute().block();
        assertThat(response).isNotNull();
        assertThat(response.getErrors()).as(response.toString()).isEmpty();
        List<Map<String, Object>> documents = response.field("searchDocuments.documents").toEntityList(Map.class)
                .stream().map(m -> (Map<String, Object>) m).toList();
        return documents.stream().map(d -> String.valueOf(d.get("id"))).toList();
    }

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
