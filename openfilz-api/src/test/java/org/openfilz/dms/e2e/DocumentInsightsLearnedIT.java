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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Tier-2 insights in {@code learned} mode, end to end: the first documents of a kind are
 * labelled by the cold-start descriptions, the user corrects them through the API, and the
 * next document of that kind takes the users' label from its neighbours — the library taught
 * its classifier, with no chat model in the loop.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
class DocumentInsightsLearnedIT extends TestContainersBaseConfig {

    private static final String DOCUMENTS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;

    DocumentInsightsLearnedIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.insights.active", () -> true);
        registry.add("openfilz.ai.insights.classifier.mode", () -> "learned");
        registry.add("openfilz.ai.insights.classifier.learned.min-neighbours", () -> 3);
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
    @DisplayName("the user's corrections teach the classifier: the next document of the kind takes their label")
    void userLabelsTeachTheNextDocument() {
        // A kind the descriptions cannot name: bank statements. Cold start calls them whatever is nearest.
        List<UUID> statements = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            statements.add(upload("releve-" + i + "-" + UUID.randomUUID() + ".txt",
                    "Relevé de compte Banque Zorg " + i + ": solde, opérations du mois, virements et prélèvements.").id());
        }
        for (UUID id : statements) {
            // Labelled by the cold start, or by whatever the shared test library already taught: either way not "other"
            DocumentInsightView view = awaitInsights(id, v -> "DONE".equals(v.status()) && v.tier() == 2);
            assertThat(view.model()).as("no chat model in learned mode").doesNotContain(":qwen").isNotEqualTo("user");
            // The user says what it is
            DocumentInsightView corrected = getWebTestClient().patch().uri(DOCUMENTS + "/" + id + "/insights")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue("{\"category\":\"Other\"}"))
                    .exchange().expectStatus().isOk().expectBody(DocumentInsightView.class).returnResult().getResponseBody();
            assertThat(corrected).isNotNull();
            assertThat(corrected.category()).isEqualTo("other");
            assertThat(corrected.model()).isEqualTo("user");
            assertThat(corrected.status()).isEqualTo("DONE");
        }

        UploadResponse next = upload("releve-next-" + UUID.randomUUID() + ".txt",
                "Relevé de compte Banque Zorg 99: solde, opérations du mois, virements et prélèvements.");
        DocumentInsightView learned = awaitInsights(next.id(), v -> "DONE".equals(v.status()) && v.tier() == 2);
        assertThat(learned.category()).as(String.valueOf(learned)).isEqualTo("other");
        assertThat(learned.model()).isEqualTo("learned:knn");
        assertThat(learned.summary()).isNull();

        // A kind the deployment does not know is refused; a document that is not visible is 404
        getWebTestClient().patch().uri(DOCUMENTS + "/" + next.id() + "/insights")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"category\":\"bank-statement\"}"))
                .exchange().expectStatus().isBadRequest();
        getWebTestClient().patch().uri(DOCUMENTS + "/" + UUID.randomUUID() + "/insights")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"category\":\"invoice\"}"))
                .exchange().expectStatus().isNotFound();
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
