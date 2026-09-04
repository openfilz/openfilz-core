package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.AiPreferencesView;
import org.openfilz.dms.dto.response.AutoFileJobView;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.FilingOutcome;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.Settings;
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
 * Smart filing on upload against the mocked models of {@link AiTestConfig} (constant embeddings,
 * so every document is equally similar and the vote is decided by counts; the mocked filing
 * model always proposes the folder "Filed-by-model" with high confidence):
 * <ol>
 *   <li>the first upload has no neighbours, so the model decides and a new folder is created;</li>
 *   <li>a folder seeded with many documents then wins the neighbour vote for a new upload;</li>
 *   <li>undo moves the batch back; autoFile=false and the user's switch are honoured;</li>
 *   <li>the settings and the filing record are exposed.</li>
 * </ol>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(AiTestConfig.class)
class AutoFileIT extends TestContainersBaseConfig {

    private static final String AUTO_FILE = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/auto-file";
    private static final String PREFERENCES = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS + "/ai/preferences";
    private static final String DOCUMENTS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;

    AutoFileIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.insights.active", () -> true);
        registry.add("openfilz.ai.auto-file.active", () -> true);
        registry.add("openfilz.ai.auto-file.wait-for-insights", () -> "10s");
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
    @DisplayName("with no similar document yet, the model decides and a new folder is created")
    void firstUploadIsFiledByTheModel() {
        Settings settings = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                .exchange().expectStatus().isOk().expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        assertThat(settings.aiAutoFileActive()).isTrue();

        UploadResponse uploaded = upload("first-" + UUID.randomUUID() + ".txt", "Annual report of ACME, revenue and outlook.", null, true);
        assertThat(uploaded.autoFile()).as("the upload response carries the filing ticket").isNotNull();

        AutoFileJobView job = awaitJob(uploaded.autoFile().jobId(), j -> "DONE".equals(j.status()));
        FilingOutcome item = job.items().getFirst();
        assertThat(item.status()).as(job.toString()).isEqualTo("FILED");
        assertThat(item.stage()).isEqualTo("MODEL");
        assertThat(item.toPath()).isEqualTo("/Filed-by-model");
        assertThat(item.confidence()).isEqualTo(0.95);

        DocumentInfo info = info(uploaded.id());
        assertThat(info.parentId()).isEqualTo(item.toFolderId());

        FilingOutcome record = getWebTestClient().get().uri(AUTO_FILE + "/document/" + uploaded.id())
                .exchange().expectStatus().isOk().expectBody(FilingOutcome.class).returnResult().getResponseBody();
        assertThat(record).isNotNull();
        assertThat(record.status()).isEqualTo("FILED");
        assertThat(record.reason()).contains("Filed-by-model");
    }

    @Test
    @Order(2)
    @DisplayName("a folder holding most of the similar documents wins the neighbour vote; undo moves the batch back")
    void neighboursDecideAndUndoRestores() {
        FolderResponse invoices = createFolder("Invoices-" + UUID.randomUUID().toString().substring(0, 8));
        for (int i = 0; i < 12; i++) {
            upload("invoice-" + i + "-" + UUID.randomUUID() + ".txt", "Invoice F-2026-00" + i + " from ACME, amount due.", invoices.id(), false);
        }
        // Give the seeded documents time to be embedded (asynchronous after the upload response)
        sleep(1500);

        UploadResponse uploaded = upload("new-invoice-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0099 from ACME, amount due.", null, true);
        assertThat(uploaded.autoFile()).isNotNull();
        AutoFileJobView job = awaitJob(uploaded.autoFile().jobId(), j -> "DONE".equals(j.status()));
        FilingOutcome item = job.items().getFirst();
        assertThat(item.status()).as(job.toString()).isEqualTo("FILED");
        assertThat(item.stage()).isEqualTo("NEIGHBOURS");
        assertThat(item.toFolderId()).isEqualTo(invoices.id());
        assertThat(item.reason()).startsWith("Similar to");
        assertThat(info(uploaded.id()).parentId()).isEqualTo(invoices.id());

        AutoFileJobView undone = getWebTestClient().post().uri(AUTO_FILE + "/" + job.jobId() + "/undo")
                .exchange().expectStatus().isOk().expectBody(AutoFileJobView.class).returnResult().getResponseBody();
        assertThat(undone).isNotNull();
        assertThat(undone.status()).isEqualTo("UNDONE");
        assertThat(undone.items().getFirst().status()).isEqualTo("UNDONE");
        assertThat(info(uploaded.id()).parentId()).as("back at the root level").isNull();

        getWebTestClient().get().uri(AUTO_FILE + "/" + UUID.randomUUID()).exchange().expectStatus().isNotFound();
    }

    @Test
    @Order(3)
    @DisplayName("autoFile=false and the user's switch are honoured; the on-demand endpoint files a selection")
    void explicitFlagAndPreference() {
        UploadResponse plain = upload("plain-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0100 from ACME.", null, false);
        assertThat(plain.autoFile()).isNull();

        UploadResponse implicit = upload("implicit-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0101 from ACME.", null, null);
        assertThat(implicit.autoFile()).as("the switch is off by default").isNull();

        AiPreferencesView saved = getWebTestClient().put().uri(PREFERENCES)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"autoFile\":true}"))
                .exchange().expectStatus().isOk().expectBody(AiPreferencesView.class).returnResult().getResponseBody();
        assertThat(saved).isNotNull();
        assertThat(saved.autoFile()).isTrue();
        assertThat(saved.autoFileAvailable()).isTrue();
        try {
            UploadResponse bySwitch = upload("switch-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0102 from ACME.", null, null);
            assertThat(bySwitch.autoFile()).as("the remembered switch files without a request flag").isNotNull();
            awaitJob(bySwitch.autoFile().jobId(), j -> "DONE".equals(j.status()));
        } finally {
            getWebTestClient().put().uri(PREFERENCES).contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue("{\"autoFile\":false}")).exchange().expectStatus().isOk();
        }

        AutoFileJobView onDemand = getWebTestClient().post().uri(AUTO_FILE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"documentIds\":[\"" + plain.id() + "\"]}"))
                .exchange().expectStatus().isOk().expectBody(AutoFileJobView.class).returnResult().getResponseBody();
        assertThat(onDemand).isNotNull();
        AutoFileJobView done = awaitJob(onDemand.jobId(), j -> "DONE".equals(j.status()));
        assertThat(done.items().getFirst().status()).as(done.toString()).isIn("FILED", "SKIPPED");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UploadResponse upload(String name, String content, UUID parentId, Boolean autoFile) {
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
        String uri = DOCUMENTS + "/upload" + (autoFile == null ? "" : "?autoFile=" + autoFile);
        return getWebTestClient().post().uri(uri)
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

    private DocumentInfo info(UUID documentId) {
        return getWebTestClient().get().uri(DOCUMENTS + "/" + documentId + "/info")
                .exchange().expectStatus().isOk().expectBody(DocumentInfo.class).returnResult().getResponseBody();
    }

    private AutoFileJobView awaitJob(UUID jobId, Predicate<AutoFileJobView> ready) {
        AutoFileJobView last = null;
        for (int attempt = 0; attempt < 240; attempt++) {
            last = getWebTestClient().get().uri(AUTO_FILE + "/" + jobId)
                    .exchange().expectStatus().isOk().expectBody(AutoFileJobView.class).returnResult().getResponseBody();
            if (last != null && ready.test(last)) {
                return last;
            }
            sleep(250);
        }
        throw new AssertionError("filing job " + jobId + " never reached the expected state; last: " + last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
