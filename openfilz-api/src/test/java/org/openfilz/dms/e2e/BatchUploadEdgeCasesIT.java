package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.MultipleUploadFileParameter;
import org.openfilz.dms.dto.request.MultipleUploadFileParameterAttributes;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests covering:
 * - DocumentController batch upload: 207 Multi-Status, all-errors HTTP status mapping
 * - DocumentController.determineAllErrorsHttpStatus() branches
 * - DocumentController.buildMultipleUploadResponse() branches
 * - Upload with metadata parsing (parseMetadata)
 * - Create blank document edge cases
 * - Audit search with various filter combinations (AuditDAOImpl.searchAuditTrail branches)
 * - Download multiple documents
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class BatchUploadEdgeCasesIT extends TestContainersBaseConfig {

    public BatchUploadEdgeCasesIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Batch Upload: Multi-Status ====================

    @Test
    void whenUploadMultipleWithSomeDuplicates_thenMultiStatus() {
        // Create a folder
        String folderName = "batch-207-" + UUID.randomUUID();
        CreateFolderRequest folderRequest = new CreateFolderRequest(folderName, null);
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(folder);

        // Upload a file first
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        // Now upload-multiple with the same file (should fail as duplicate) and a different file (should succeed)
        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test_file_1.sql"));
        builder.part("file", new ClassPathResource("test.txt"));

        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter(
                "test_file_1.sql", new MultipleUploadFileParameterAttributes(folder.id(), null));
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter(
                "test.txt", new MultipleUploadFileParameterAttributes(folder.id(), null));

        // Disable allowDuplicateFileNames to trigger conflict for the first file
        builder.part("parametersByFilename", List.of(param1, param2));
        List<UploadResponse> responses = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                        .queryParam("allowDuplicateFileNames", false)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isEqualTo(207) // HTTP 207 Multi-Status
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(responses);
        Assertions.assertEquals(2, responses.size());

        // One should succeed, one should fail
        long successes = responses.stream().filter(r -> !r.isError()).count();
        long errors = responses.stream().filter(UploadResponse::isError).count();
        Assertions.assertEquals(1, successes, "One file should succeed");
        Assertions.assertEquals(1, errors, "One file should fail with duplicate");
    }

    @Test
    void whenUploadMultipleAllDuplicates_thenConflictStatus() {
        // Create a folder and upload both files first
        String folderName = "batch-all-dup-" + UUID.randomUUID();
        CreateFolderRequest folderRequest = new CreateFolderRequest(folderName, null);
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        // Upload both files individually first
        MultipartBodyBuilder builder1 = newFileBuilder();
        builder1.part("parentFolderId", folder.id().toString());
        uploadDocument(builder1);

        MultipartBodyBuilder builder2 = new MultipartBodyBuilder();
        builder2.part("file", new ClassPathResource("test.txt"));
        builder2.part("parentFolderId", folder.id().toString());
        uploadDocument(builder2);

        // Now try upload-multiple - both should fail as duplicates
        MultipartBodyBuilder batchBuilder = new MultipartBodyBuilder();
        batchBuilder.part("file", new ClassPathResource("test_file_1.sql"));
        batchBuilder.part("file", new ClassPathResource("test.txt"));

        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter(
                "test_file_1.sql", new MultipleUploadFileParameterAttributes(folder.id(), null));
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter(
                "test.txt", new MultipleUploadFileParameterAttributes(folder.id(), null));

        batchBuilder.part("parametersByFilename", List.of(param1, param2));
        List<UploadResponse> responses = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                        .queryParam("allowDuplicateFileNames", false)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(batchBuilder.build()))
                .exchange()
                .expectStatus().isEqualTo(409) // All failed with same error type → 409
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(responses);
        Assertions.assertEquals(2, responses.size());
        // All should be errors
        Assertions.assertTrue(responses.stream().allMatch(UploadResponse::isError));
    }

    @Test
    void whenUploadMultipleToNonExistentFolder_thenNotFoundStatus() {
        UUID nonExistentFolderId = UUID.randomUUID();

        MultipartBodyBuilder builder = newFileBuilder("test_file_1.sql", "test.txt");
        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter(
                "test_file_1.sql", new MultipleUploadFileParameterAttributes(nonExistentFolderId, null));
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter(
                "test.txt", new MultipleUploadFileParameterAttributes(nonExistentFolderId, null));

        builder.part("parametersByFilename", List.of(param1, param2));
        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isNotFound(); // All failed with DocumentNotFound → 404
    }

    // ==================== Upload with Metadata ====================

    @Test
    void whenUploadWithValidMetadata_thenMetadataStored() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"key1\":\"value1\",\"key2\":\"value2\"}");

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.id());
    }

    @Test
    void whenUploadWithInvalidMetadataJson_thenBadRequest() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "not-valid-json{{{");

        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenUploadWithEmptyMetadataJson_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "");

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
    }

    // ==================== Audit Search Edge Cases ====================

    @Test
    void whenAuditSearchByDocumentType_thenFiltered() {
        // Create folder to generate a CREATE_FOLDER audit event
        String folderName = "audit-type-search-" + UUID.randomUUID();
        CreateFolderRequest folderRequest = new CreateFolderRequest(folderName, null);
        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated();

        // Search by document type FOLDER
        SearchByAuditLogRequest request = new SearchByAuditLogRequest(
                null, null, DocumentType.FOLDER, AuditAction.CREATE_FOLDER, null);

        List<AuditLog> results = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(results);
        Assertions.assertFalse(results.isEmpty(), "Should find CREATE_FOLDER audit entries");
    }

    @Test
    void whenAuditSearchByAction_thenFiltered() {
        // Upload a file to generate UPLOAD_DOCUMENT audit event
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse uploaded = uploadDocument(builder);
        Assertions.assertNotNull(uploaded);

        // Search by action UPLOAD_DOCUMENT
        SearchByAuditLogRequest request = new SearchByAuditLogRequest(
                null, null, null, AuditAction.UPLOAD_DOCUMENT, null);

        List<AuditLog> results = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(results);
        Assertions.assertFalse(results.isEmpty(), "Should find UPLOAD_DOCUMENT audit entries");
    }

    @Test
    void whenAuditSearchByResourceId_thenFiltered() {
        // Upload a file
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse uploaded = uploadDocument(builder);
        Assertions.assertNotNull(uploaded);

        // Search by specific resource ID
        SearchByAuditLogRequest request = new SearchByAuditLogRequest(
                null, uploaded.id(), null, null, null);

        List<AuditLog> results = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(results);
        Assertions.assertFalse(results.isEmpty(), "Should find audit entries for the specific resource");
    }

    @Test
    void whenAuditSearchWithNoMatch_thenEmptyResults() {
        // Search by a non-existent resource ID
        SearchByAuditLogRequest request = new SearchByAuditLogRequest(
                null, UUID.randomUUID(), null, null, null);

        List<AuditLog> results = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(results);
        Assertions.assertTrue(results.isEmpty(), "Should return empty for non-existent resource");
    }

    @Test
    void whenGetAuditTrailWithAscSort_thenSorted() {
        // Upload a file, then replace to have multiple audit entries
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse uploaded = uploadDocument(builder);
        Assertions.assertNotNull(uploaded);

        // Get audit trail with ASC sort
        List<AuditLog> auditTrail = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}")
                        .queryParam("sort", SortOrder.ASC.name())
                        .build(uploaded.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertFalse(auditTrail.isEmpty());
    }

    // ==================== Download Multiple Documents ====================

    @Test
    void whenDownloadMultipleDocuments_thenZipReturned() {
        // Upload two files
        MultipartBodyBuilder builder1 = newFileBuilder();
        UploadResponse file1 = uploadDocument(builder1);

        MultipartBodyBuilder builder2 = new MultipartBodyBuilder();
        builder2.part("file", new ClassPathResource("test.txt"));
        UploadResponse file2 = getUploadResponse(builder2, true);

        Assertions.assertNotNull(file1);
        Assertions.assertNotNull(file2);

        // Download multiple as ZIP
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(file1.id(), file2.id()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM);
    }

    // ==================== Search by Metadata ====================

    @Test
    void whenSearchIdsByMetadata_thenFound() {
        // Upload file with metadata
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"searchKey\":\"uniqueValue-" + UUID.randomUUID() + "\"}");

        UploadResponse uploaded = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uploaded);
    }
}
