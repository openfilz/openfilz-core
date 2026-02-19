package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AuditProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.audit.AuditVerificationResult;
import org.openfilz.dms.dto.audit.AuditVerificationResult.AuditVerificationStatus;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.service.AuditChainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.openfilz.dms.enums.AuditAction.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class AuditChainIT extends TestContainersBaseConfig {

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private AuditChainService auditChainService;

    @Autowired
    private AuditProperties auditProperties;

    public AuditChainIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @BeforeEach
    void resetExcludedActions() {
        // Ensure no actions are excluded by default for each test
        auditProperties.setExcludedActions(Set.of());
    }

    // ==================== Chain Genesis ====================

    @Test
    void chainGenesis_isInsertedOnStartup() {
        // The CHAIN_GENESIS entry should have been inserted by ApplicationReadyEvent
        List<AuditLog> results = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(new SearchByAuditLogRequest(null, null, null, CHAIN_GENESIS, null)))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(results);
        Assertions.assertFalse(results.isEmpty(), "CHAIN_GENESIS entry should exist");

        AuditLog genesis = results.getFirst();
        Assertions.assertEquals(CHAIN_GENESIS, genesis.action());
        Assertions.assertNotNull(genesis.hash(), "CHAIN_GENESIS entry should have a hash");
        Assertions.assertNotNull(genesis.previousHash(), "CHAIN_GENESIS entry should have a previousHash");
    }

    // ==================== Verify Endpoint ====================

    @Test
    void verifyChain_returnsValid() {
        AuditVerificationResult result = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/verify")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuditVerificationResult.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertEquals(AuditVerificationStatus.VALID, result.status());
        Assertions.assertTrue(result.totalEntries() > 0, "Should have at least the CHAIN_GENESIS entry");
        Assertions.assertEquals(result.totalEntries(), result.verifiedEntries());
        Assertions.assertNotNull(result.verifiedAt());
        Assertions.assertNull(result.brokenLink());
    }

    // ==================== Hash Fields in Audit Trail ====================

    @Test
    void auditTrail_containsHashFields() {
        // Create a folder to generate an auditable action
        CreateFolderRequest folderRequest = new CreateFolderRequest("audit-chain-hash-test", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(folderResponse);

        // Retrieve audit trail for this resource
        List<AuditLog> auditTrail = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}").build(folderResponse.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertFalse(auditTrail.isEmpty());

        for (AuditLog entry : auditTrail) {
            Assertions.assertNotNull(entry.hash(), "Each chained entry must have a hash");
            Assertions.assertNotNull(entry.previousHash(), "Each chained entry must have a previousHash");
            Assertions.assertEquals(64, entry.hash().length(), "Hash must be 64-char SHA-256 hex");
            Assertions.assertEquals(64, entry.previousHash().length(), "PreviousHash must be 64-char SHA-256 hex");
        }
    }

    @Test
    void searchAuditTrail_containsHashFields() {
        // Upload a file
        MultipartBodyBuilder builder = newFileBuilder("test.txt");
        UploadResponse uploadResponse = uploadDocument(builder);
        Assertions.assertNotNull(uploadResponse);

        // Search by action
        List<AuditLog> results = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(new SearchByAuditLogRequest(null, uploadResponse.id(), null, UPLOAD_DOCUMENT, null)))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(results);
        Assertions.assertFalse(results.isEmpty());

        AuditLog entry = results.getFirst();
        Assertions.assertNotNull(entry.hash());
        Assertions.assertNotNull(entry.previousHash());
    }

    // ==================== Chain Integrity After Multiple Operations ====================

    @Test
    void chainRemainsValid_afterMultipleOperations() {
        // Perform several auditable operations
        CreateFolderRequest folderRequest = new CreateFolderRequest("chain-multi-ops", null);

        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        // Upload a file into the folder
        MultipartBodyBuilder builder = newFileBuilder("test.txt");
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);
        Assertions.assertNotNull(file);

        // Delete the folder (cascades)
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new DeleteRequest(Collections.singletonList(folder.id()))))
                .exchange()
                .expectStatus().isNoContent();

        // Verify the entire chain is still valid
        AuditVerificationResult result = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/verify")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuditVerificationResult.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertEquals(AuditVerificationStatus.VALID, result.status());
        Assertions.assertNull(result.brokenLink());
    }

    // ==================== Hash Chain Linkage ====================

    @Test
    void hashChain_previousHashLinksToParentEntry() {
        // Create two folders to generate two consecutive audit entries for known resources
        FolderResponse folder1 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest("chain-link-test-1", null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        FolderResponse folder2 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest("chain-link-test-2", null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        // Get audit entries for both folders
        List<AuditLog> trail1 = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}").build(folder1.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        List<AuditLog> trail2 = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}").build(folder2.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(trail1);
        Assertions.assertNotNull(trail2);
        Assertions.assertFalse(trail1.isEmpty());
        Assertions.assertFalse(trail2.isEmpty());

        AuditLog entry1 = trail1.getFirst();
        AuditLog entry2 = trail2.getFirst();

        // folder2 was created after folder1, so entry2.previousHash should equal entry1.hash
        Assertions.assertEquals(entry1.hash(), entry2.previousHash(),
                "Second entry's previousHash should equal first entry's hash (chain linkage)");
    }

    // ==================== Tamper Detection ====================

    @Test
    void verifyChain_detectsTampering() {
        // First verify chain is valid
        AuditVerificationResult beforeTamper = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/verify")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuditVerificationResult.class)
                .returnResult().getResponseBody();

        Assertions.assertEquals(AuditVerificationStatus.VALID, beforeTamper.status());

        // Tamper: drop the trigger, modify an entry, recreate the trigger
        databaseClient.sql("DROP TRIGGER IF EXISTS audit_log_immutable ON audit_logs").then().block();
        databaseClient.sql("UPDATE audit_logs SET hash = 'tampered_hash_value_0000000000000000000000000000000000' WHERE id = (SELECT id FROM audit_logs WHERE hash IS NOT NULL ORDER BY id ASC LIMIT 1)")
                .then().block();

        // Verify chain is now broken
        AuditVerificationResult afterTamper = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/verify")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuditVerificationResult.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(afterTamper);
        Assertions.assertEquals(AuditVerificationStatus.BROKEN, afterTamper.status());
        Assertions.assertNotNull(afterTamper.brokenLink());
        Assertions.assertEquals(1, afterTamper.brokenLink().entryId(), "First chained entry should be the broken one");
        Assertions.assertEquals("tampered_hash_value_0000000000000000000000000000000000", afterTamper.brokenLink().actualHash());

        // Restore: fix the tampered entry by recomputing the hash, then recreate trigger
        // We need to fix the entry to not leave the DB in a bad state for other tests.
        // The simplest approach: delete all chained entries and re-initialize.
        databaseClient.sql("DELETE FROM audit_logs WHERE hash IS NOT NULL").then().block();
        databaseClient.sql("""
                CREATE OR REPLACE FUNCTION prevent_audit_log_mutation() RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION 'Audit log entries are immutable and cannot be modified or deleted';
                END;
                $$ LANGUAGE plpgsql
                """).then().block();
        databaseClient.sql("""
                CREATE TRIGGER audit_log_immutable
                    BEFORE UPDATE OR DELETE ON audit_logs
                    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation()
                """).then().block();

        // Re-insert genesis so subsequent tests work
        String genesisHash = auditChainService.computeGenesisHash();
        databaseClient.sql("DROP TRIGGER IF EXISTS audit_log_immutable ON audit_logs").then().block();
        // Temporarily remove trigger to insert genesis
        databaseClient.sql("""
                INSERT INTO audit_logs (timestamp, user_principal, action, resource_type, resource_id, previous_hash, hash)
                VALUES (NOW(), 'SYSTEM', 'CHAIN_GENESIS', NULL, NULL, :ph, :h)
                """)
                .bind("ph", genesisHash)
                .bind("h", auditChainService.computeHash(null, null, null, null, null, null, null))
                .then().block();
        // Recreate trigger
        databaseClient.sql("""
                CREATE TRIGGER audit_log_immutable
                    BEFORE UPDATE OR DELETE ON audit_logs
                    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation()
                """).then().block();
    }

    // ==================== Immutability Trigger ====================

    @Test
    void immutabilityTrigger_preventsUpdate() {
        // Try to UPDATE an audit_logs entry — the trigger should reject it
        try {
            databaseClient.sql("UPDATE audit_logs SET action = 'MOVE_FILE' WHERE id = (SELECT id FROM audit_logs LIMIT 1)")
                    .then().block();
            Assertions.fail("UPDATE on audit_logs should have been rejected by trigger");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("immutable") || e.getMessage().contains("cannot be modified"),
                    "Exception should mention immutability: " + e.getMessage());
        }
    }

    @Test
    void immutabilityTrigger_preventsDelete() {
        // Try to DELETE an audit_logs entry — the trigger should reject it
        try {
            databaseClient.sql("DELETE FROM audit_logs WHERE id = (SELECT id FROM audit_logs LIMIT 1)")
                    .then().block();
            Assertions.fail("DELETE on audit_logs should have been rejected by trigger");
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("immutable") || e.getMessage().contains("cannot be modified"),
                    "Exception should mention immutability: " + e.getMessage());
        }
    }

    // ==================== Excluded Actions ====================

    @Test
    void excludedActions_areNotAudited() {
        // Exclude CREATE_FOLDER from auditing
        auditProperties.setExcludedActions(Set.of(CREATE_FOLDER));

        String uniqueFolderName = "excluded-action-test-" + UUID.randomUUID();

        // Create a folder — this should NOT generate an audit entry
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest(uniqueFolderName, null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(folder);

        // Search for audit entries for this resource
        List<AuditLog> trail = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}").build(folder.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(trail);
        Assertions.assertTrue(trail.isEmpty(), "No audit entry should exist for excluded action CREATE_FOLDER");

        // Verify chain is still valid (excluded actions don't break the chain)
        AuditVerificationResult result = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/verify")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuditVerificationResult.class)
                .returnResult().getResponseBody();

        Assertions.assertEquals(AuditVerificationStatus.VALID, result.status());
    }

    @Test
    void nonExcludedActions_areStillAudited() {
        // Exclude only DOWNLOAD_DOCUMENT — CREATE_FOLDER should still be audited
        auditProperties.setExcludedActions(Set.of(DOWNLOAD_DOCUMENT));

        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest("non-excluded-test-" + UUID.randomUUID(), null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(folder);

        List<AuditLog> trail = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}").build(folder.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(trail);
        Assertions.assertFalse(trail.isEmpty(), "CREATE_FOLDER should be audited when not excluded");
        Assertions.assertEquals(CREATE_FOLDER, trail.getFirst().action());
    }

    @Test
    void emptyExclusionList_auditsEverything() {
        auditProperties.setExcludedActions(Set.of());

        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest("empty-exclusion-test-" + UUID.randomUUID(), null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        List<AuditLog> trail = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}").build(folder.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(trail);
        Assertions.assertFalse(trail.isEmpty(), "All actions should be audited when exclusion list is empty");
    }

    // ==================== Concurrent Audit Events ====================

    @Test
    void concurrentAuditEvents_chainRemainsValid() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Create several folders concurrently
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                            .body(BodyInserters.fromValue(new CreateFolderRequest("concurrent-test-" + idx, null)))
                            .exchange()
                            .expectStatus().isCreated();
                } finally {
                    latch.countDown();
                }
            });
        }

        Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), "All concurrent operations should complete");
        executor.shutdown();

        // Verify the chain is still valid after concurrent operations
        AuditVerificationResult result = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/verify")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuditVerificationResult.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertEquals(AuditVerificationStatus.VALID, result.status(),
                "Chain must remain valid after concurrent audit events");
        Assertions.assertNull(result.brokenLink());
    }
}
