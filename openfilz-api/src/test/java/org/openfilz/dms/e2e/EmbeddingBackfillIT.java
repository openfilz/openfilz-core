package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.EmbeddingBackfillStatus;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * The embedding backfill, end to end against a real {@link PgVectorStore} on the test Postgres
 * (pgvector) — the store the candidate query reads, which an in-memory store could not prove.
 * The scenario is the operator's provider switch: the store is wiped, the backfill embeds what is
 * missing under a folder, a second run finds nothing to do, a forced run embeds everything again.
 * The unknown-job answer is checked too; the role gate lives in {@code AiSecurityIT}
 * (this base context runs without authentication). The embedding model is the
 * deterministic bag-of-words one; no chat model is involved (insights stay off).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import({AiChatMockConfig.class, EmbeddingBackfillIT.PgVectorTestStore.class})
class EmbeddingBackfillIT extends TestContainersBaseConfig {

    private static final String DOCUMENTS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;
    private static final String EMBEDDINGS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/embeddings";

    /** The real store over the Flyway-managed {@code vector_store} table, on the deterministic test embedding. */
    @TestConfiguration
    static class PgVectorTestStore {
        @Bean
        @Primary
        EmbeddingModel testEmbeddingModel() {
            return new AiTestConfig.BagOfWordsEmbeddingModel();
        }

        @Bean
        @Primary
        VectorStore testVectorStore(@Qualifier("aiJdbcTemplate") JdbcTemplate aiJdbcTemplate, EmbeddingModel testEmbeddingModel) {
            return PgVectorStore.builder(aiJdbcTemplate, testEmbeddingModel)
                    .dimensions(AiTestConfig.BagOfWordsEmbeddingModel.DIMENSIONS)
                    .initializeSchema(false)
                    .build();
        }
    }

    @Autowired
    @Qualifier("aiJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    EmbeddingBackfillIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.embedding.backfill-concurrency", () -> 2);
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
    @DisplayName("after a wipe, the backfill embeds the files without chunks under a folder; nothing twice unless forced")
    void backfillEmbedsWhatIsMissing() {
        FolderResponse folder = createFolder("embed-backfill-" + UUID.randomUUID());
        UploadResponse first = upload("contract-" + UUID.randomUUID() + ".txt",
                "Contract between ACME and Globex for consulting services, signed in Paris.", folder.id());
        UploadResponse second = upload("invoice-" + UUID.randomUUID() + ".txt",
                "Invoice F-2026-0042 from ACME to Globex, total due 1 440 EUR, payment within 30 days.", folder.id());
        awaitChunks(first.id(), count -> count > 0);
        awaitChunks(second.id(), count -> count > 0);

        // The operator's wipe (the whole store in real life; here the two rows, since suites share the database):
        // a direct DB write is the scenario under test — no API deletes vectors on purpose.
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'document_id' IN (?, ?)",
                first.id().toString(), second.id().toString());
        assertThat(chunkCount(first.id())).isZero();
        assertThat(chunkCount(second.id())).isZero();

        EmbeddingBackfillStatus finished = awaitJob(startBackfill(folder.id(), false), s -> "DONE".equals(s.status()));
        assertThat(finished.folderId()).isEqualTo(folder.id());
        assertThat(finished.force()).isFalse();
        assertThat(finished.total()).isEqualTo(2);
        assertThat(finished.done()).isEqualTo(2);
        assertThat(finished.failed()).isZero();
        assertThat(finished.skipped()).isZero();
        assertThat(finished.finishedAt()).isNotNull();
        assertThat(chunkCount(first.id())).isPositive();
        assertThat(chunkCount(second.id())).isPositive();

        // Nothing is missing any more: a second run has no candidate
        EmbeddingBackfillStatus idle = awaitJob(startBackfill(folder.id(), false), s -> "DONE".equals(s.status()));
        assertThat(idle.total()).isZero();
        assertThat(idle.done()).isZero();

        // Forced: every file of the folder again, the old chunks replaced rather than piled up
        EmbeddingBackfillStatus forced = awaitJob(startBackfill(folder.id(), true), s -> "DONE".equals(s.status()));
        assertThat(forced.force()).isTrue();
        assertThat(forced.total()).isEqualTo(2);
        assertThat(forced.done()).isEqualTo(2);
        assertThat(chunkCount(first.id())).isEqualTo(1);
        assertThat(chunkCount(second.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown job is 404 (the role gate is proven in AiSecurityIT, where authentication is on)")
    void unknownJob() {
        getWebTestClient().get().uri(EMBEDDINGS + "/backfill/" + UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private EmbeddingBackfillStatus startBackfill(UUID folderId, boolean force) {
        EmbeddingBackfillStatus started = getWebTestClient().post().uri(EMBEDDINGS + "/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"folderId\":\"" + folderId + "\",\"force\":" + force + "}"))
                .exchange().expectStatus().isOk()
                .expectBody(EmbeddingBackfillStatus.class).returnResult().getResponseBody();
        assertThat(started).isNotNull();
        assertThat(started.jobId()).isNotNull();
        return started;
    }

    private EmbeddingBackfillStatus awaitJob(EmbeddingBackfillStatus started, Predicate<EmbeddingBackfillStatus> ready) {
        EmbeddingBackfillStatus last = started;
        for (int attempt = 0; attempt < 240; attempt++) {
            last = getWebTestClient().get().uri(EMBEDDINGS + "/backfill/" + started.jobId())
                    .exchange().expectStatus().isOk()
                    .expectBody(EmbeddingBackfillStatus.class).returnResult().getResponseBody();
            if (last != null && ready.test(last)) {
                return last;
            }
            sleep();
        }
        throw new AssertionError("backfill job " + started.jobId() + " never reached the expected state; last: " + last);
    }

    /** Chunks tagged with the document: the internal column the job's outcome is read from. */
    private int chunkCount(UUID documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?", Integer.class, documentId.toString());
        return count == null ? 0 : count;
    }

    private void awaitChunks(UUID documentId, Predicate<Integer> ready) {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (ready.test(chunkCount(documentId))) {
                return;
            }
            sleep();
        }
        throw new AssertionError("document " + documentId + " was never embedded");
    }

    private UploadResponse upload(String name, String content, UUID parentId) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        }).contentType(MediaType.TEXT_PLAIN);
        builder.part("parentFolderId", parentId.toString());
        return getWebTestClient().post().uri(DOCUMENTS + "/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
    }

    private FolderResponse createFolder(String name) {
        return getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new CreateFolderRequest(name, null)))
                .exchange().expectStatus().isCreated()
                .expectBody(FolderResponse.class).returnResult().getResponseBody();
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
