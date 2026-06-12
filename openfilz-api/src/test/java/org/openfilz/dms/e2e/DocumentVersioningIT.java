package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.DocumentVersionInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.RestoreVersionResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for the document version endpoints (list / download / restore)
 * with MinIO storage + bucket versioning enabled.
 *
 * All state is set up and asserted through the REST APIs (upload, replace-content,
 * versions, audit) — no direct DB or MinIO access.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class DocumentVersioningIT extends TestContainersBaseConfig {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    public DocumentVersioningIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.minio.endpoint", minio::getS3URL);
        registry.add("storage.minio.access-key", minio::getUserName);
        registry.add("storage.minio.secret-key", minio::getPassword);
        registry.add("storage.type", () -> "minio");
        registry.add("storage.minio.versioning-enabled", () -> true);
    }

    @Test
    void whenUploadAndReplaceTwice_thenThreeVersionsNewestFirst() {
        UUID id = uploadThenReplace("test.txt", "test-data.csv");

        List<DocumentVersionInfo> versions = listVersions(id);
        Assertions.assertEquals(3, versions.size(), "Should have 3 versions after 2 replacements");
        Assertions.assertEquals(1, versions.stream().filter(DocumentVersionInfo::latest).count(),
                "Exactly one version must be flagged latest");
        Assertions.assertTrue(versions.getFirst().latest(), "Newest version must be first");
        for (int i = 1; i < versions.size(); i++) {
            Assertions.assertFalse(versions.get(i).lastModified().isAfter(versions.get(i - 1).lastModified()),
                    "Versions must be sorted newest first");
        }
        versions.forEach(v -> {
            Assertions.assertNotNull(v.versionId());
            Assertions.assertNotNull(v.size());
        });
    }

    @Test
    void whenUploadAndReplace_thenAuditEntriesCarryVersionIds() {
        UUID id = uploadThenReplace("test.txt", "test-data.csv");

        List<DocumentVersionInfo> versions = listVersions(id);
        List<String> versionIds = versions.stream().map(DocumentVersionInfo::versionId).toList();

        List<Map<String, Object>> auditLogs = getAuditTrail(id);
        List<String> auditVersionIds = auditLogs.stream()
                .filter(log -> "UPLOAD_DOCUMENT".equals(log.get("action")) || "REPLACE_DOCUMENT_CONTENT".equals(log.get("action")))
                .map(this::detailsVersionId)
                .toList();

        Assertions.assertEquals(3, auditVersionIds.size(), "Upload + 2 replaces expected in audit trail");
        auditVersionIds.forEach(versionId -> Assertions.assertNotNull(versionId,
                "Each version-creating audit entry must carry details.versionId"));
        Assertions.assertEquals(3, auditVersionIds.stream().distinct().count(), "Audit versionIds must be distinct");
        auditVersionIds.forEach(versionId -> Assertions.assertTrue(versionIds.contains(versionId),
                "Audit versionId " + versionId + " must exist in the versions list"));
    }

    @Test
    void whenDownloadOldestVersion_thenOriginalContentReturned() throws Exception {
        UUID id = uploadThenReplace("test.txt");

        List<DocumentVersionInfo> versions = listVersions(id);
        Assertions.assertEquals(2, versions.size());
        DocumentVersionInfo oldest = versions.getLast();
        Assertions.assertFalse(oldest.latest());

        byte[] downloaded = downloadVersion(id, oldest.versionId());
        byte[] original = new ClassPathResource("test_file_1.sql").getContentAsByteArray();
        Assertions.assertArrayEquals(original, downloaded,
                "Oldest version content must equal the originally uploaded file");
    }

    @Test
    void whenRestoreOldestVersion_thenHistoryPreservedAndAuditLogged() throws Exception {
        UUID id = uploadThenReplace("test.txt");

        List<DocumentVersionInfo> versionsBefore = listVersions(id);
        Assertions.assertEquals(2, versionsBefore.size());
        DocumentVersionInfo oldest = versionsBefore.getLast();

        // Restore the oldest version
        RestoreVersionResponse response = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions/{versionId}/restore", id, oldest.versionId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(RestoreVersionResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertEquals(id, response.documentId());
        Assertions.assertEquals(oldest.versionId(), response.restoredFromVersionId());
        Assertions.assertNotNull(response.restoredFromDate());
        Assertions.assertNotNull(response.newVersionId());
        Assertions.assertNotEquals(oldest.versionId(), response.newVersionId(),
                "Restore must create a NEW version");

        // History preserved: 3 versions now, every pre-restore version still downloadable
        List<DocumentVersionInfo> versionsAfter = listVersions(id);
        Assertions.assertEquals(3, versionsAfter.size(), "Restore must add a version, not delete any");
        Assertions.assertTrue(versionsAfter.stream().anyMatch(v -> v.versionId().equals(response.newVersionId()) && v.latest()),
                "The new version must be the latest");
        for (DocumentVersionInfo version : versionsBefore) {
            downloadVersion(id, version.versionId());
        }

        // Current content equals the restored (original) content
        byte[] original = new ClassPathResource("test_file_1.sql").getContentAsByteArray();
        byte[] current = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult().getResponseBody();
        Assertions.assertArrayEquals(original, current, "Current content must equal the restored version");

        // Document size updated to the restored version's size
        Long size = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .queryParam("withMetadata", true).build(id))
                .exchange()
                .expectStatus().isOk()
                .expectBody(org.openfilz.dms.dto.response.DocumentInfo.class)
                .returnResult().getResponseBody().size();
        Assertions.assertEquals(oldest.size(), size, "Document size must match the restored version");

        // Audit: RESTORE_DOCUMENT_VERSION entry referencing the restored version + the new version
        List<Map<String, Object>> auditLogs = getAuditTrail(id);
        Map<String, Object> restoreEntry = auditLogs.stream()
                .filter(log -> "RESTORE_DOCUMENT_VERSION".equals(log.get("action")))
                .findFirst().orElse(null);
        Assertions.assertNotNull(restoreEntry, "A RESTORE_DOCUMENT_VERSION audit entry must be logged");
        Map<String, Object> details = detailsOf(restoreEntry);
        Assertions.assertEquals(oldest.versionId(), details.get("restoredFromVersionId"));
        Assertions.assertNotNull(details.get("restoredFromDate"));
        Assertions.assertEquals(response.newVersionId(), details.get("versionId"));
        Assertions.assertEquals("restoreVersion", details.get("type"));
    }

    @Test
    void whenRestoreLatestVersion_thenBadRequest() {
        UUID id = uploadThenReplace("test.txt");

        DocumentVersionInfo latest = listVersions(id).getFirst();
        Assertions.assertTrue(latest.latest());

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions/{versionId}/restore", id, latest.versionId())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenUnknownDocumentOrVersion_thenNotFound() {
        // unknown document
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();

        // unknown versionId on an existing document
        UUID id = uploadThenReplace();
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions/{versionId}/download", id, "00000000-0000-0000-0000-000000000000")
                .exchange()
                .expectStatus().isNotFound();
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions/{versionId}/restore", id, "00000000-0000-0000-0000-000000000000")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenListVersionsOfFolder_thenForbidden() {
        CreateFolderRequest folderRequest = new CreateFolderRequest("versions-folder-" + UUID.randomUUID(), null);
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions", folder.id())
                .exchange()
                .expectStatus().isForbidden();
    }

    // ==================== Helper Methods ====================

    /**
     * Uploads test_file_1.sql then replaces its content once per given classpath resource.
     */
    private UUID uploadThenReplace(String... replacementFiles) {
        UploadResponse uploaded = uploadDocument(newFileBuilder());
        Assertions.assertNotNull(uploaded);
        for (String replacement : replacementFiles) {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ClassPathResource(replacement));
            getWebTestClient().put()
                    .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", uploaded.id())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .expectStatus().isOk();
        }
        return uploaded.id();
    }

    private List<DocumentVersionInfo> listVersions(UUID documentId) {
        return getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions", documentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<DocumentVersionInfo>>() {})
                .returnResult().getResponseBody();
    }

    private byte[] downloadVersion(UUID documentId, String versionId) {
        return getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions/{versionId}/download", documentId, versionId)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Content-Disposition", "attachment; filename=\".*\"")
                .expectBody(byte[].class)
                .returnResult().getResponseBody();
    }

    private List<Map<String, Object>> getAuditTrail(UUID documentId) {
        return getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/{id}", documentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .returnResult().getResponseBody()
                .stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detailsOf(Map<String, Object> auditLog) {
        return (Map<String, Object>) auditLog.get("details");
    }

    private String detailsVersionId(Map<String, Object> auditLog) {
        Map<String, Object> details = detailsOf(auditLog);
        return details != null ? (String) details.get("versionId") : null;
    }
}
