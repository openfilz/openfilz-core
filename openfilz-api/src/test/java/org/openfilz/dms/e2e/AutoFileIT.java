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
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.dto.response.FilingOutcome;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.insight.InsightCompletionSignal;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Smart filing on upload against the mocked models of {@link AiTestConfig} (constant embeddings,
 * a hashed bag of words, so documents sharing words are neighbours; the mocked filing
 * model always proposes the folder "Filed-by-model" with high confidence):
 * <ol>
 *   <li>a document of no known kind and no neighbours goes to the model, which creates a folder;</li>
 *   <li>a document of a known kind with no home goes to the folder of its kind (the rule, no model);</li>
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

    @Autowired
    private InsightCompletionSignal insightSignal;

    AutoFileIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.insights.active", () -> true);
        registry.add("openfilz.ai.auto-file.active", () -> true);
        registry.add("openfilz.ai.auto-file.wait-for-insights", () -> "10s");
        // Wide enough for every test document to be a neighbour of the one being filed
        registry.add("openfilz.ai.auto-file.neighbour-top-k", () -> 200);
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
    @DisplayName("with no similar document and no known kind, the model decides and a new folder is created")
    void firstUploadIsFiledByTheModel() {
        Settings settings = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                .exchange().expectStatus().isOk().expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        assertThat(settings.aiAutoFileActive()).isTrue();

        // "Miscellaneous" makes the insight mock answer the category "other": the rule stage has no folder for it
        UploadResponse uploaded = upload("first-" + UUID.randomUUID() + ".txt", "Miscellaneous notes of ACME, odds and ends.", null, true);
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
        // The filing waited on the insight signal, then let go of its registration
        assertThat(insightSignal.pending()).as("no insight waiter left behind").isZero();
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

    @Test
    @Order(4)
    @DisplayName("documents lying at the root never vote to keep a new upload there")
    void rootNeighboursNeverKeepADocumentAtTheRoot() {
        // A batch dropped at the root. The seeded texts are near-identical, so every one is a
        // close neighbour of the new upload, and if root documents could vote, the root would win
        // outright here and the new upload would be "already in the folder where its closest
        // documents live" — the trap a batch of similar files uploaded together used to fall in.
        for (int i = 0; i < 40; i++) {
            upload("loose-" + i + "-" + UUID.randomUUID() + ".txt", "Board minutes " + i + " of ACME, decisions and actions.", null, false);
        }
        sleep(1500);

        UploadResponse uploaded = upload("loose-new-" + UUID.randomUUID() + ".txt", "Board minutes 99 of ACME, decisions and actions.", null, true);
        assertThat(uploaded.autoFile()).isNotNull();
        AutoFileJobView job = awaitJob(uploaded.autoFile().jobId(), j -> "DONE".equals(j.status()));
        FilingOutcome item = job.items().getFirst();
        assertThat(item.reason()).as(job.toString()).doesNotContain("already in the folder");
        assertThat(item.status()).as(job.toString()).isEqualTo("FILED");
        assertThat(item.toFolderId()).isNotNull();
        assertThat(info(uploaded.id()).parentId()).as("moved out of the root").isEqualTo(item.toFolderId());
    }

    @Test
    @Order(5)
    @DisplayName("a mixed folder never wins the vote: an invoice whose neighbours live among reports goes to the folder of its kind")
    void mixedFolderHandsOverToTheRule() {
        // A grab-bag folder — invoices and reports side by side — holds the majority of the
        // invoices, so it wins the headcount. It is no home for an invoice (barely half of its
        // files are, by category and by similarity alike), so the vote is discarded and the rule
        // creates the folder this kind deserves, named in the library's language — English here,
        // as no root folder is named in any language and the insight mock says "en".
        FolderResponse mixed = createFolder("Mixed-" + UUID.randomUUID().toString().substring(0, 8));
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            seeded.add(upload("mixed-invoice-" + i + "-" + UUID.randomUUID() + ".txt",
                    "Invoice F-2026-05" + i + " from Globex, amount due.", mixed.id(), false).id());
        }
        for (int i = 0; i < 25; i++) {
            seeded.add(upload("mixed-report-" + i + "-" + UUID.randomUUID() + ".txt",
                    "Monthly report " + i + " of Globex, figures and outlook.", mixed.id(), false).id());
        }
        // The vote reads the neighbours' categories: wait for their tier-2 rows, not just the embeddings
        for (UUID id : seeded) {
            awaitTier2(id);
        }

        UploadResponse uploaded = upload("mixed-new-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0599 from Globex, amount due.", null, true);
        assertThat(uploaded.autoFile()).isNotNull();
        AutoFileJobView job = awaitJob(uploaded.autoFile().jobId(), j -> "DONE".equals(j.status()));
        FilingOutcome item = job.items().getFirst();
        assertThat(item.status()).as(job.toString()).isEqualTo("FILED");
        assertThat(item.stage()).as("the mixed folder was refused and the rule decided").isEqualTo("RULE");
        assertThat(item.toFolderId()).isNotEqualTo(mixed.id());
        assertThat(item.toPath()).isEqualTo("/Invoices");
        assertThat(item.reason()).contains("invoice");
        assertThat(info(uploaded.id()).parentId()).isEqualTo(item.toFolderId());

        // The next invoice with the same neighbours finds the folder by name: no second "Invoices"
        UploadResponse next = upload("mixed-next-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0600 from Globex, amount due.", null, true);
        FilingOutcome nextItem = awaitJob(next.autoFile().jobId(), j -> "DONE".equals(j.status())).items().getFirst();
        assertThat(nextItem.status()).as(nextItem.toString()).isEqualTo("FILED");
        assertThat(nextItem.toFolderId()).as("the same Invoices folder, by name or by vote").isEqualTo(item.toFolderId());
    }

    @Test
    @Order(6)
    @DisplayName("a document of a known kind with no close neighbour goes to the folder of its kind, named in the scope's language")
    void ruleNamesTheFolderInTheLibrarysLanguage() {
        // A French library: its folders are named in French, so the new folder for a report is "Rapports"
        FolderResponse fr = createFolder("FR-" + UUID.randomUUID().toString().substring(0, 8));
        createFolder("Contrats", fr.id());
        createFolder("Courriers", fr.id());
        UploadResponse report = upload("rapport-" + UUID.randomUUID() + ".txt",
                "Rapport annuel de la société Globex, chiffre d'affaires et perspectives.", fr.id(), true);
        assertThat(report.autoFile()).isNotNull();
        FilingOutcome filed = awaitJob(report.autoFile().jobId(), j -> "DONE".equals(j.status())).items().getFirst();
        assertThat(filed.status()).as(filed.toString()).isEqualTo("FILED");
        assertThat(filed.stage()).isEqualTo("RULE");
        assertThat(filed.toPath()).isEqualTo("/" + fr.name() + "/Rapports");
        assertThat(filed.reason()).contains("named in fr");
        assertThat(info(report.id()).parentId()).isEqualTo(filed.toFolderId());

        // An existing folder that denotes the kind, in any language, is found by name — even a German one
        FolderResponse de = createFolder("DE-" + UUID.randomUUID().toString().substring(0, 8));
        FolderResponse rechnungen = createFolder("Rechnungen", de.id());
        UploadResponse invoice = upload("rechnung-" + UUID.randomUUID() + ".txt",
                "Invoice F-2026-0810 an Stark GmbH, Betrag fällig.", de.id(), true);
        FilingOutcome found = awaitJob(invoice.autoFile().jobId(), j -> "DONE".equals(j.status())).items().getFirst();
        assertThat(found.status()).as(found.toString()).isEqualTo("FILED");
        assertThat(found.stage()).isEqualTo("RULE");
        assertThat(found.toFolderId()).isEqualTo(rechnungen.id());
        assertThat(info(invoice.id()).parentId()).isEqualTo(rechnungen.id());

        // Creating folders off: the rule has nothing to offer, the model decides
        FolderResponse noNew = createFolder("NoNew-" + UUID.randomUUID().toString().substring(0, 8));
        UploadResponse loose = upload("loose-" + UUID.randomUUID() + ".txt", "Quarterly report of Initech, results.", noNew.id(), false);
        AutoFileJobView job = getWebTestClient().post().uri(AUTO_FILE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"documentIds\":[\"" + loose.id() + "\"],\"allowNewFolders\":false}"))
                .exchange().expectStatus().isOk().expectBody(AutoFileJobView.class).returnResult().getResponseBody();
        assertThat(job).isNotNull();
        FilingOutcome byModel = awaitJob(job.jobId(), j -> "DONE".equals(j.status())).items().getFirst();
        assertThat(byModel.stage()).as(byModel.toString()).isEqualTo("MODEL");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Wait for a document's tier-2 insight row; the row appears only once the upload's indexing ran. */
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

    private FolderResponse createFolder(String name, UUID parentId) {
        return getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new CreateFolderRequest(name, parentId)))
                .exchange().expectStatus().isCreated().expectBody(FolderResponse.class).returnResult().getResponseBody();
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
