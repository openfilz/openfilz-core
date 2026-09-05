package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.dto.response.UploadResponse;
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
 * Tier-2 insights in {@code prototype} mode: the category comes from the embedding model alone
 * (the nearest built-in description), the row is category-only and records the classifier as
 * its model, and no chat model is consulted — the whole pipeline runs with embeddings only.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
class DocumentInsightsPrototypeIT extends TestContainersBaseConfig {

    private static final String DOCUMENTS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;

    DocumentInsightsPrototypeIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.insights.active", () -> true);
        registry.add("openfilz.ai.insights.classifier.mode", () -> "prototype");
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
    @DisplayName("an upload is categorised by its nearest prototype, with no summary and the classifier as the model")
    void categoryByPrototype() {
        UploadResponse invoice = upload("facture-" + UUID.randomUUID() + ".txt",
                "Invoice F-2026-0042 from ACME: amount due 1 200 EUR, VAT 20 %, payment terms 30 days.");
        DocumentInsightView view = awaitInsights(invoice.id(), v -> "DONE".equals(v.status()) && v.tier() == 2);
        assertThat(view.category()).isEqualTo("invoice");
        assertThat(view.model()).startsWith("prototype:");
        assertThat(view.summary()).as("category-only: the prototype classifier writes no summary").isNull();
        assertThat(view.keywords()).isEmpty();
        assertThat(view.entities()).isNullOrEmpty();
        assertThat(view.promptVersion()).isEqualTo(1);

        UploadResponse report = upload("report-" + UUID.randomUUID() + ".txt",
                "Quarterly report of ACME: analysis, findings, figures and conclusions on the period.");
        DocumentInsightView reportView = awaitInsights(report.id(), v -> "DONE".equals(v.status()) && v.tier() == 2);
        assertThat(reportView.category()).isEqualTo("report");
        assertThat(reportView.model()).startsWith("prototype:");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private DocumentInsightView awaitInsights(UUID documentId, Predicate<DocumentInsightView> ready) {
        DocumentInsightView last = null;
        for (int attempt = 0; attempt < 240; attempt++) {
            var result = getWebTestClient().get().uri(DOCUMENTS + "/" + documentId + "/insights")
                    .exchange().returnResult(DocumentInsightView.class);
            if (result.getStatus().is2xxSuccessful()) {
                last = result.getResponseBody().blockFirst();
                if (last != null && ready.test(last)) {
                    return last;
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("insights of " + documentId + " never became ready; last: " + last);
    }

    private UploadResponse upload(String name, String content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        }).contentType(MediaType.TEXT_PLAIN);
        return getWebTestClient().post().uri(DOCUMENTS + "/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated().expectBody(UploadResponse.class).returnResult().getResponseBody();
    }
}
