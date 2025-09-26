package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.MultipleUploadFileParameter;
import org.openfilz.dms.dto.request.MultipleUploadFileParameterAttributes;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class MinioIT extends LocalStorageIT {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    public MinioIT(WebTestClient webTestClient) {
        super(webTestClient);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.minio.endpoint", minio::getS3URL);
        registry.add("storage.minio.access-key", minio::getUserName);
        registry.add("storage.minio.secret-key", minio::getPassword);
        registry.add("storage.type", () -> "minio");
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


        Resource resource = webTestClient.get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", folder.id())
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
