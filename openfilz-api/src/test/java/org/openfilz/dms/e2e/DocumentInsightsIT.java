package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Tier-1 document insights: the file's own metadata is captured from the Tika pass that the
 * AI embedding already runs (full-text is off here, so this is the standalone embedding path),
 * served over {@code GET /documents/{id}/insights} and shown by the {@code getMetadata} tool.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
class DocumentInsightsIT extends TestContainersBaseConfig {

    @Autowired
    private DocumentAiTools documentAiTools;

    DocumentInsightsIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    /** Same shape as DocumentAiToolsIT so the Spring context is shared. */
    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
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
    @DisplayName("a PDF upload gets a tier-1 insight row with its page count, served over REST and in getMetadata")
    void pdfUploadProducesTier1Insights() {
        UploadResponse uploaded = uploadDocument(newFileBuilder("pdf-example.pdf"));

        DocumentInsightView view = awaitInsights(uploaded.id());

        assertThat(view.documentId()).isEqualTo(uploaded.id());
        assertThat(view.tier()).isEqualTo(1);
        assertThat(view.status()).isEqualTo("DONE");
        assertThat(view.pageCount()).as("a PDF always carries its page count").isGreaterThanOrEqualTo(1);
        assertThat(view.category()).as("tier 2 is off in this context").isNull();

        String tool = documentAiTools.getMetadata(uploaded.id().toString());
        assertThat(tool).contains("Insights").contains("\"pageCount\":" + view.pageCount());
    }

    @Test
    @DisplayName("an unknown document answers 404")
    void unknownDocumentIs404() {
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS + "/" + UUID.randomUUID() + "/insights")
                .exchange()
                .expectStatus().isNotFound();
    }

    /** Insights are written asynchronously after the upload response; poll briefly. */
    private DocumentInsightView awaitInsights(UUID documentId) {
        String uri = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS + "/" + documentId + "/insights";
        DocumentInsightView last = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            var result = getWebTestClient().get().uri(uri).exchange().returnResult(DocumentInsightView.class);
            if (result.getStatus().is2xxSuccessful()) {
                last = result.getResponseBody().blockFirst();
                if (last != null) {
                    return last;
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("no insights for " + documentId + " after 15 s");
    }
}
