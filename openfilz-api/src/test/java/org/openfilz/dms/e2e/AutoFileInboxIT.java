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
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.filing.DestinationRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The policy stage of smart filing and the Inbox convention (design §13.5), against the mocked
 * models of {@link AiTestConfig} (the filing model always proposes "Filed-by-model"):
 * <ol>
 *   <li>the preferences create the caller's Inbox on demand, in the language of the request,
 *       reuse it, and forget it without deleting it;</li>
 *   <li>a document dropped in the Inbox is filed with the whole library as scope, never into
 *       the Inbox itself;</li>
 *   <li>{@code POST /ai/auto-file/inbox} files what lies loose in the Inbox, and answers 404
 *       to a user who has none;</li>
 *   <li>a {@link DestinationRule} bean names the destination outright (stage POLICY), stays
 *       dry when asked to, and cannot create a folder it was not allowed to.</li>
 * </ol>
 * Its own context: the Inbox switch is on here and off in {@link AutoFileIT}, which pins the
 * 400s of a deployment that does not offer it.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import({AiTestConfig.class, AutoFileInboxIT.TestRules.class})
class AutoFileInboxIT extends TestContainersBaseConfig {

    private static final String AUTO_FILE = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/auto-file";
    private static final String PREFERENCES = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS + "/ai/preferences";
    private static final String DOCUMENTS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;

    /** The rule of the policy tests: it only ever answers for documents named {@code rule-…}. */
    static final String TEST_RULE_ID = "test-rule";
    static final String TEST_RULE_TARGET = "Rules/Test";

    @Autowired
    private DatabaseClient databaseClient;

    AutoFileInboxIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.insights.active", () -> true);
        registry.add("openfilz.ai.auto-file.active", () -> true);
        registry.add("openfilz.ai.auto-file.inbox.enabled", () -> true);
        registry.add("openfilz.ai.auto-file.wait-for-insights", () -> "10s");
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

    /**
     * An extension's rule as the core sees it: a bean, ordered after the Inbox rule. Documents
     * named {@code rule-dry-…} get a dry decision, {@code rule-nocreate-…} a target it may not
     * create, any other {@code rule-…} the target with creation allowed; everything else is not
     * its business.
     */
    @TestConfiguration
    static class TestRules {
        @Bean
        DestinationRule testDestinationRule() {
            return new DestinationRule() {
                @Override
                public int order() {
                    return 10;
                }

                @Override
                public Optional<Decision> decide(FilingContext context) {
                    String name = context.document().getName();
                    if (name == null || !name.startsWith("rule-")) {
                        return Optional.empty();
                    }
                    assertThat(context.metadata()).as("the rule sees the document's metadata, never null").isNotNull();
                    assertThat(context.scopePath()).as("the readable scope").isNotNull();
                    if (name.startsWith("rule-dry-")) {
                        return Optional.of(Decision.target(TEST_RULE_TARGET, true, true, TEST_RULE_ID, "a dry policy"));
                    }
                    if (name.startsWith("rule-nocreate-")) {
                        return Optional.of(Decision.target("Rules/Missing-" + UUID.randomUUID(), false, false, TEST_RULE_ID, "a policy that may not create"));
                    }
                    return Optional.of(Decision.target(TEST_RULE_TARGET, true, false, TEST_RULE_ID, "filed by the test policy"));
                }
            };
        }
    }

    @Test
    @Order(1)
    @DisplayName("the preferences create the Inbox on demand in the request's language, reuse it, and forget it without deleting it")
    void preferencesCreateReuseAndForgetTheInbox() {
        AiPreferencesView before = getPreferences();
        assertThat(before.autoFileAvailable()).isTrue();
        assertThat(before.inboxAvailable()).as("the deployment offers the Inbox").isTrue();
        assertThat(before.inbox()).as("nobody has one yet").isFalse();
        assertThat(before.inboxFolderId()).isNull();

        AiPreferencesView created = savePreferences("{\"inbox\":true}", "fr-FR,fr;q=0.9,en;q=0.8");
        assertThat(created.inbox()).isTrue();
        assertThat(created.inboxFolderId()).isNotNull();
        DocumentInfo folder = info(created.inboxFolderId());
        assertThat(folder.name()).as("named in the language of the request").isEqualTo("Boîte de réception");
        assertThat(folder.parentId()).as("at the user's root").isNull();

        AiPreferencesView again = savePreferences("{\"inbox\":true}", "de");
        assertThat(again.inboxFolderId()).as("an existing Inbox is kept, whatever the language now").isEqualTo(created.inboxFolderId());
        assertThat(getPreferences().inboxFolderId()).isEqualTo(created.inboxFolderId());

        AiPreferencesView forgotten = savePreferences("{\"inbox\":false}", null);
        assertThat(forgotten.inbox()).isFalse();
        assertThat(forgotten.inboxFolderId()).isNull();
        assertThat(info(created.inboxFolderId()).name()).as("the folder itself is never deleted").isEqualTo("Boîte de réception");

        // Turned on again with the same language: the root folder bearing that name is reused, not duplicated
        AiPreferencesView reused = savePreferences("{\"inbox\":true}", "fr");
        assertThat(reused.inboxFolderId()).isEqualTo(created.inboxFolderId());
        // A null field leaves the Inbox alone
        assertThat(savePreferences("{\"autoFile\":false}", null).inboxFolderId()).isEqualTo(created.inboxFolderId());
    }

    @Test
    @Order(2)
    @DisplayName("a document dropped in the Inbox is filed with the whole library as scope, never into the Inbox")
    void inboxWidensTheScopeAndIsNeverADestination() {
        UUID inbox = savePreferences("{\"inbox\":true}", "fr").inboxFolderId();
        assertThat(inbox).isNotNull();
        // Loose siblings already in the Inbox: unfiled ground, they never vote to keep the new one there
        upload("inbox-sibling-a-" + UUID.randomUUID() + ".txt", "Miscellaneous notes of Initech, odds and ends.", inbox, false);
        upload("inbox-sibling-b-" + UUID.randomUUID() + ".txt", "Miscellaneous notes of Initech, odds and ends.", inbox, false);
        sleep(1000);

        UploadResponse uploaded = upload("inbox-new-" + UUID.randomUUID() + ".txt", "Miscellaneous notes of Initech, odds and ends.", inbox, true);
        assertThat(uploaded.autoFile()).isNotNull();
        AutoFileJobView job = awaitJob(uploaded.autoFile().jobId(), j -> "DONE".equals(j.status()));
        FilingOutcome item = job.items().getFirst();
        assertThat(item.status()).as(job.toString()).isEqualTo("FILED");
        assertThat(item.toFolderId()).as("never the Inbox").isNotEqualTo(inbox);
        assertThat(item.toPath()).as("the library is the scope: the model's folder lands at the root, not under the Inbox")
                .isEqualTo("/Filed-by-model");
        assertThat(item.fromFolderId()).isEqualTo(inbox);
        assertThat(info(uploaded.id()).parentId()).isEqualTo(item.toFolderId());
        // The filing record remembers the effective scope (the library) and, for a rule, its id
        assertThat(planDetails(item.planId())).contains("\"scope\"").doesNotContain("\"ruleId\"");
    }

    @Test
    @Order(3)
    @DisplayName("POST /auto-file/inbox files what lies loose in the Inbox; a user without one gets 404")
    void fileInboxEndpoint() {
        UUID inbox = savePreferences("{\"inbox\":true}", "fr").inboxFolderId();
        assertThat(inbox).isNotNull();
        UploadResponse first = upload("loose-a-" + UUID.randomUUID() + ".txt", "Miscellaneous notes of Umbrella, odds and ends.", inbox, false);
        UploadResponse second = upload("loose-b-" + UUID.randomUUID() + ".txt", "Miscellaneous notes of Umbrella, odds and ends.", inbox, false);
        assertThat(first.autoFile()).isNull();
        // A sub-folder someone made in the Inbox, and its content, is theirs to organise: not part of the job
        FolderResponse sub = createFolder("Keep-" + UUID.randomUUID().toString().substring(0, 8), inbox);
        UploadResponse kept = upload("kept-" + UUID.randomUUID() + ".txt", "Miscellaneous notes of Umbrella, odds and ends.", sub.id(), false);
        sleep(1000);

        AutoFileJobView job = getWebTestClient().post().uri(AUTO_FILE + "/inbox")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue("{\"allowNewFolders\":true}"))
                .exchange().expectStatus().isOk().expectBody(AutoFileJobView.class).returnResult().getResponseBody();
        assertThat(job).isNotNull();
        AutoFileJobView done = awaitJob(job.jobId(), j -> "DONE".equals(j.status()));
        assertThat(done.items().stream().map(FilingOutcome::documentId))
                .as("only the loose files of the Inbox, whatever else lies in it: " + done)
                .contains(first.id(), second.id()).doesNotContain(kept.id(), sub.id());
        for (FilingOutcome item : done.items()) {
            assertThat(item.status()).as(item.toString()).isEqualTo("FILED");
            assertThat(item.toFolderId()).isNotEqualTo(inbox);
        }
        assertThat(info(first.id()).parentId()).isNotEqualTo(inbox);
        assertThat(info(kept.id()).parentId()).as("untouched").isEqualTo(sub.id());

        // No body at all is fine: the user's preference decides about new folders
        AutoFileJobView empty = getWebTestClient().post().uri(AUTO_FILE + "/inbox")
                .exchange().expectStatus().isOk().expectBody(AutoFileJobView.class).returnResult().getResponseBody();
        assertThat(empty).isNotNull();
        assertThat(awaitJob(empty.jobId(), j -> "DONE".equals(j.status())).items()).as("nothing loose is left").isEmpty();

        // Without an Inbox there is nothing to file: 404, not an empty job
        savePreferences("{\"inbox\":false}", null);
        getWebTestClient().post().uri(AUTO_FILE + "/inbox").exchange().expectStatus().isNotFound();
    }

    @Test
    @Order(4)
    @DisplayName("a DestinationRule names the destination before the vote: stage POLICY, dry runs stay put, no creation unless allowed")
    void destinationRuleDecidesBeforeTheVote() {
        FolderResponse scope = createFolder("Policy-" + UUID.randomUUID().toString().substring(0, 8), null);

        // The rule targets Rules/Test relative to the scope, and may create it
        UploadResponse ruled = upload("rule-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0901 from ACME, amount due.", scope.id(), true);
        assertThat(ruled.autoFile()).isNotNull();
        FilingOutcome filed = awaitJob(ruled.autoFile().jobId(), j -> "DONE".equals(j.status())).items().getFirst();
        assertThat(filed.status()).as(filed.toString()).isEqualTo("FILED");
        assertThat(filed.stage()).isEqualTo(FilingOutcome.STAGE_POLICY);
        assertThat(filed.confidence()).isEqualTo(1.0);
        assertThat(filed.reason()).isEqualTo("filed by the test policy");
        assertThat(filed.toPath()).isEqualTo("/" + scope.name() + "/" + TEST_RULE_TARGET);
        assertThat(info(ruled.id()).parentId()).isEqualTo(filed.toFolderId());
        assertThat(planDetails(filed.planId())).as("the rule's id and the scope are in the filing record")
                .contains("\"ruleId\": \"" + TEST_RULE_ID + "\"").contains("\"scope\": \"" + scope.id() + "\"");

        // Dry: the outcome says what the rule would have done, the document does not move
        UploadResponse dry = upload("rule-dry-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0902 from ACME, amount due.", scope.id(), true);
        FilingOutcome skipped = awaitJob(dry.autoFile().jobId(), j -> "DONE".equals(j.status())).items().getFirst();
        assertThat(skipped.status()).as(skipped.toString()).isEqualTo("SKIPPED");
        assertThat(skipped.stage()).isEqualTo(FilingOutcome.STAGE_POLICY);
        assertThat(skipped.reason()).isEqualTo("rule " + TEST_RULE_ID + " would file it into /" + scope.name() + "/" + TEST_RULE_TARGET + " (dry run)");
        assertThat(info(dry.id()).parentId()).isEqualTo(scope.id());

        // A missing target the rule may not create: skipped with the reason, the vote is not consulted
        UploadResponse noCreate = upload("rule-nocreate-" + UUID.randomUUID() + ".txt", "Invoice F-2026-0903 from ACME, amount due.", scope.id(), true);
        FilingOutcome refused = awaitJob(noCreate.autoFile().jobId(), j -> "DONE".equals(j.status())).items().getFirst();
        assertThat(refused.status()).as(refused.toString()).isEqualTo("SKIPPED");
        assertThat(refused.stage()).isEqualTo(FilingOutcome.STAGE_POLICY);
        assertThat(refused.reason()).contains("does not exist").contains("may not create it");
        assertThat(info(noCreate.id()).parentId()).isEqualTo(scope.id());

        // The rule stays out of the other documents' way: an ordinary upload goes through the usual stages
        UploadResponse plain = upload("plain-" + UUID.randomUUID() + ".txt", "Miscellaneous notes of ACME, odds and ends.", scope.id(), true);
        FilingOutcome usual = awaitJob(plain.autoFile().jobId(), j -> "DONE".equals(j.status())).items().getFirst();
        assertThat(usual.stage()).as(usual.toString()).isNotEqualTo(FilingOutcome.STAGE_POLICY);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * The filing record's {@code details} JSON. A deliberate DB seam: the decision details (rule
     * id, effective scope) have no read endpoint — {@code GET /ai/auto-file/document/{id}} shows
     * the outcome, not the record's internals.
     */
    private String planDetails(UUID planId) {
        assertThat(planId).isNotNull();
        String details = databaseClient.sql("SELECT details::text AS details FROM ai_reorganization_plans WHERE id = :id")
                .bind("id", planId)
                .map((row, meta) -> row.get("details", String.class))
                .one().block();
        assertThat(details).isNotNull();
        return details;
    }

    private AiPreferencesView getPreferences() {
        AiPreferencesView view = getWebTestClient().get().uri(PREFERENCES)
                .exchange().expectStatus().isOk().expectBody(AiPreferencesView.class).returnResult().getResponseBody();
        assertThat(view).isNotNull();
        return view;
    }

    private AiPreferencesView savePreferences(String body, String acceptLanguage) {
        WebTestClient.RequestBodySpec spec = getWebTestClient().put().uri(PREFERENCES).contentType(MediaType.APPLICATION_JSON);
        if (acceptLanguage != null) {
            spec = spec.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        }
        AiPreferencesView view = spec.body(BodyInserters.fromValue(body))
                .exchange().expectStatus().isOk().expectBody(AiPreferencesView.class).returnResult().getResponseBody();
        assertThat(view).isNotNull();
        return view;
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
