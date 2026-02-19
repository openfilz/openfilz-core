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
import org.openfilz.dms.enums.DocumentType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class MinioIT extends LocalStorageIT {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    public MinioIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }


    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.minio.endpoint", minio::getS3URL);
        registry.add("storage.minio.access-key", minio::getUserName);
        registry.add("storage.minio.secret-key", minio::getPassword);
        registry.add("storage.type", () -> "minio");
        registry.add("storage.minio.versioning-enabled", () -> true);
    }

    protected MinioClient createMinioClient() {
        return MinioClient.builder()
                .endpoint(minio.getS3URL())
                .credentials(minio.getUserName(), minio.getPassword())
                .build();
    }

    /**
     * Lists all versions of a given object in the bucket.
     */
    protected List<Item> listObjectVersions(String bucketName, String objectName) throws Exception {
        MinioClient client = createMinioClient();
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

    @Test
    void whenReplaceContent_thenVersionCreated() throws Exception {
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

        // 5. Verify MinIO now has 2 versions of the same object
        List<Item> versionsAfter = listObjectVersions("dms-bucket", storagePath);
        Assertions.assertEquals(2, versionsAfter.size(), "MinIO must have 2 versions after replace");
    }

    @Test
    void whenDownloadFolder_thenOK() throws IOException {
        String folderName = "whenDownloadFolder_thenOK";
        CreateFolderRequest createFolderRequest = new CreateFolderRequest(folderName, null);

        FolderResponse folder = webTestClient.post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        ClassPathResource file1 = new ClassPathResource("test_file_1.sql");
        ClassPathResource file2 = new ClassPathResource("test.txt");
        builder.part("file", file1);
        builder.part("file", file2);

        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter("test_file_1.sql", new MultipleUploadFileParameterAttributes(folder.id(), null));
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter("test.txt", new MultipleUploadFileParameterAttributes(folder.id(), null));

        List<UploadResponse> uploadResponse = getUploadMultipleDocumentExchange(param1, param2, builder)
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uploadResponse);

        String subfolderName = "SubFolder" + UUID.randomUUID();
        createFolderRequest = new CreateFolderRequest(subfolderName, folder.id());

        FolderResponse subFolder = webTestClient.post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        builder = new MultipartBodyBuilder();
        builder.part("file", file1);
        builder.part("file", file2);

        param1 = new MultipleUploadFileParameter("test_file_1.sql", new MultipleUploadFileParameterAttributes(subFolder.id(), null));
        param2 = new MultipleUploadFileParameter("test.txt", new MultipleUploadFileParameterAttributes(subFolder.id(), null));

        uploadResponse = getUploadMultipleDocumentExchange(param1, param2, builder)
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uploadResponse);


        Resource resource = webTestClient.mutate().responseTimeout(Duration.ofSeconds(30)).build()
                .get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", folder.id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectBody(Resource.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(resource);
        Assertions.assertEquals(folderName + ".zip", resource.getFilename());

        checkFilesInZip(resource, file1, file2, subfolderName);
    }

}
