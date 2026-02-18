package org.openfilz.dms.e2e;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class MinioWithSignatureIT extends AbstractStorageWithSignatureIT {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    public MinioWithSignatureIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
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
    void whenReplaceContent_withChecksum_thenVersionCreated() throws Exception {
        // 1. Upload original file (test_file_1.sql)
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse originalUploadResponse = uploadDocument(builder);
        Assertions.assertNotNull(originalUploadResponse);
        UUID id = originalUploadResponse.id();
        Long originalSize = originalUploadResponse.size();
        Assertions.assertTrue(originalSize != null && originalSize > 0);

        // Get the storage path from DB before replace
        String storagePath = databaseClient.sql("select storage_path from documents where id = :id")
                .bind("id", id)
                .map(row -> row.get("storage_path", String.class))
                .one()
                .block();
        Assertions.assertNotNull(storagePath);

        // Verify only 1 version exists before replace
        List<Item> versionsBefore = listObjectVersions("dms-bucket", storagePath);
        Assertions.assertEquals(1, versionsBefore.size());

        // 2. Replace content with a different file (test.txt)
        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        getWebTestClient().put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content")
                        .build(id.toString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("test_file_1.sql")
                .jsonPath("$.type").isEqualTo(DocumentType.FILE)
                .jsonPath("$.id").isEqualTo(id.toString());

        // 3. Verify the storage path is unchanged (same object, new version)
        String storagePathAfter = databaseClient.sql("select storage_path from documents where id = :id")
                .bind("id", id)
                .map(row -> row.get("storage_path", String.class))
                .one()
                .block();
        Assertions.assertEquals(storagePath, storagePathAfter, "Storage path must remain the same when versioning is enabled");

        // 4. Verify the file size has changed (content was replaced)
        DocumentInfo info = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(id.toString()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(info);
        Assertions.assertTrue(info.size() != null && info.size() > 0 && !info.size().equals(originalSize),
                "File size must differ after content replacement");

        // 5. Verify the checksum has been updated
        Assertions.assertNotNull(info.metadata());
        Assertions.assertNotNull(info.metadata().get("sha256"), "Checksum must be present when checksum is enabled");
        Assertions.assertEquals(test_txt_sha, info.metadata().get("sha256"),
                "Checksum must match the new file (test.txt) after replacement");

        // 6. Verify MinIO now has 2 versions of the same object
        List<Item> versionsAfter = listObjectVersions("dms-bucket", storagePath);
        Assertions.assertEquals(2, versionsAfter.size(), "MinIO must have 2 versions after replace");
    }

    private List<Item> listObjectVersions(String bucketName, String objectName) throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(minio.getS3URL())
                .credentials(minio.getUserName(), minio.getPassword())
                .build();
        List<Item> versions = new ArrayList<>();
        for (Result<Item> result : client.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucketName)
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
