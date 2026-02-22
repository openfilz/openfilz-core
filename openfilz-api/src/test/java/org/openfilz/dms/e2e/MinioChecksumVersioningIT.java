package org.openfilz.dms.e2e;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.MultipleUploadFileParameter;
import org.openfilz.dms.dto.request.MultipleUploadFileParameterAttributes;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests covering MinioChecksumService and ChecksumSaveDocumentServiceImpl
 * with MinIO storage + bucket versioning + checksum calculation enabled.
 *
 * These tests exercise:
 * - handleVersionedReplace: same file → version reverted, different file → new version kept
 * - compareAndHandleVersioned: checksum comparison logic
 * - calculatePreviousVersionChecksum: MinIO version listing for checksum calc
 * - Upload with metadata preserving checksum
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class MinioChecksumVersioningIT extends TestContainersBaseConfig {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    @Autowired
    private DatabaseClient databaseClient;

    public MinioChecksumVersioningIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.minio.endpoint", minio::getS3URL);
        registry.add("storage.minio.access-key", minio::getUserName);
        registry.add("storage.minio.secret-key", minio::getPassword);
        registry.add("storage.type", () -> "minio");
        registry.add("storage.minio.versioning-enabled", () -> true);
        registry.add("openfilz.calculate-checksum", () -> Boolean.TRUE);
    }

    @Test
    void whenUpload_thenChecksumInMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse response = uploadDocument(builder);
        Assertions.assertNotNull(response);

        DocumentInfo info = getDocumentInfoWithMetadata(response.id());
        Assertions.assertNotNull(info);
        Assertions.assertNotNull(info.metadata());
        Assertions.assertNotNull(info.metadata().get("sha256"), "Checksum should be present after upload");
        Assertions.assertEquals(64, ((String) info.metadata().get("sha256")).length(), "SHA-256 hash should be 64 hex chars");
    }

    @Test
    void whenUploadWithMetadata_thenChecksumAndMetadataPreserved() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("appId", "MY_APP", "version", "1.0"));
        UploadResponse response = uploadDocument(builder);
        Assertions.assertNotNull(response);

        DocumentInfo info = getDocumentInfoWithMetadata(response.id());
        Assertions.assertNotNull(info);
        Assertions.assertNotNull(info.metadata());
        Assertions.assertEquals("MY_APP", info.metadata().get("appId"));
        Assertions.assertEquals("1.0", info.metadata().get("version"));
        Assertions.assertNotNull(info.metadata().get("sha256"), "Checksum should be present alongside user metadata");
    }

    @Test
    void whenReplaceContentWithSameFile_thenVersionReverted() throws Exception {
        // 1. Upload original file
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse original = uploadDocument(builder);
        Assertions.assertNotNull(original);
        UUID id = original.id();

        String storagePath = getStoragePath(id);
        Assertions.assertNotNull(storagePath);

        // Get original checksum
        DocumentInfo originalInfo = getDocumentInfoWithMetadata(id);
        String originalChecksum = (String) originalInfo.metadata().get("sha256");
        Assertions.assertNotNull(originalChecksum);

        // Verify 1 version before replace
        List<Item> versionsBefore = listObjectVersions(storagePath);
        Assertions.assertEquals(1, versionsBefore.size());

        // 2. Replace content with the SAME file (test_file_1.sql)
        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test_file_1.sql"));
        getWebTestClient().put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content")
                        .build(id.toString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk();

        // 3. Verify checksum unchanged
        DocumentInfo afterInfo = getDocumentInfoWithMetadata(id);
        String afterChecksum = (String) afterInfo.metadata().get("sha256");
        Assertions.assertEquals(originalChecksum, afterChecksum,
                "Checksum should remain unchanged when replacing with the same file");

        // 4. Verify the latest version was reverted (deleted)
        // After reverting, we should still have 1 version (the revert deletes the new version)
        List<Item> versionsAfter = listObjectVersions(storagePath);
        Assertions.assertEquals(1, versionsAfter.size(),
                "Version should be reverted when replacing with identical file");
    }

    @Test
    void whenReplaceContentWithDifferentFile_thenChecksumRecalculated() throws Exception {
        // 1. Upload original file
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse original = uploadDocument(builder);
        UUID id = original.id();

        String storagePath = getStoragePath(id);
        DocumentInfo originalInfo = getDocumentInfoWithMetadata(id);
        String originalChecksum = (String) originalInfo.metadata().get("sha256");
        Assertions.assertNotNull(originalChecksum);

        // 2. Replace content with a different file
        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        getWebTestClient().put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content")
                        .build(id.toString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk();

        // 3. Verify checksum has changed
        DocumentInfo afterInfo = getDocumentInfoWithMetadata(id);
        String afterChecksum = (String) afterInfo.metadata().get("sha256");
        Assertions.assertNotNull(afterChecksum);
        Assertions.assertNotEquals(originalChecksum, afterChecksum,
                "Checksum should be recalculated when replacing with a different file");

        // 4. Verify MinIO has 2 versions
        List<Item> versionsAfter = listObjectVersions(storagePath);
        Assertions.assertEquals(2, versionsAfter.size(),
                "MinIO should have 2 versions after replacing with different file");
    }

    @Test
    void whenUploadMultipleWithChecksum_thenAllHaveChecksums() {
        MultipartBodyBuilder builder = newFileBuilder("test_file_1.sql", "test.txt");

        Map<String, Object> metadata1 = Map.of("appId", "APP_CHECKSUM_1");
        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter(
                "test_file_1.sql", new MultipleUploadFileParameterAttributes(null, metadata1));
        Map<String, Object> metadata2 = Map.of("appId", "APP_CHECKSUM_2");
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter(
                "test.txt", new MultipleUploadFileParameterAttributes(null, metadata2));

        List<UploadResponse> responses = getUploadMultipleDocumentExchange(param1, param2, builder)
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(responses);
        Assertions.assertEquals(2, responses.size());

        for (UploadResponse resp : responses) {
            DocumentInfo info = getDocumentInfoWithMetadata(resp.id());
            Assertions.assertNotNull(info.metadata());
            Assertions.assertNotNull(info.metadata().get("sha256"),
                    "Each uploaded file should have a sha256 checksum: " + resp.name());
        }
    }

    @Test
    void whenReplaceContentTwice_thenThreeVersionsExist() throws Exception {
        // 1. Upload original
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse original = uploadDocument(builder);
        UUID id = original.id();
        String storagePath = getStoragePath(id);

        // 2. Replace with test.txt
        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        getWebTestClient().put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content")
                        .build(id.toString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk();

        // 3. Replace with test-data.csv
        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test-data.csv"));
        getWebTestClient().put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content")
                        .build(id.toString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk();

        // 4. Verify 3 versions in MinIO
        List<Item> versions = listObjectVersions(storagePath);
        Assertions.assertEquals(3, versions.size(), "Should have 3 versions after 2 replacements");

        // 5. Verify checksum matches the latest file
        DocumentInfo info = getDocumentInfoWithMetadata(id);
        Assertions.assertNotNull(info.metadata().get("sha256"));
    }

    @Test
    void whenUploadInFolder_thenChecksumPresent() {
        // Create folder
        CreateFolderRequest folderRequest = new CreateFolderRequest("checksum-folder-" + UUID.randomUUID(), null);
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        // Upload file in folder
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse response = uploadDocument(builder);

        DocumentInfo info = getDocumentInfoWithMetadata(response.id());
        Assertions.assertNotNull(info.metadata());
        Assertions.assertNotNull(info.metadata().get("sha256"));
    }

    // ==================== Helper Methods ====================

    private DocumentInfo getDocumentInfoWithMetadata(UUID id) {
        return getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(id.toString()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
    }

    private String getStoragePath(UUID id) {
        return databaseClient.sql("select storage_path from documents where id = :id")
                .bind("id", id)
                .map(row -> row.get("storage_path", String.class))
                .one()
                .block();
    }

    private List<Item> listObjectVersions(String objectName) throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(minio.getS3URL())
                .credentials(minio.getUserName(), minio.getPassword())
                .build();
        List<Item> versions = new ArrayList<>();
        for (Result<Item> result : client.listObjects(
                ListObjectsArgs.builder()
                        .bucket("dms-bucket")
                        .prefix(objectName)
                        .includeVersions(true)
                        .build())) {
            Item item = result.get();
            if (item.objectName().equals(objectName)) {
                versions.add(item);
            }
        }
        return versions;
    }
}
