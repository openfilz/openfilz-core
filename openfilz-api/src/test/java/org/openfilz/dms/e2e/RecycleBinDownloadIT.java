package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The recycle-bin page downloads a trashed file through {@code GET /documents/{id}/download}: a
 * soft-deleted file the caller sees in their bin is downloadable, nothing else that is inactive is,
 * and every other read path stays active-only.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class RecycleBinDownloadIT extends TestContainersBaseConfig {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.soft-delete.active", () -> true);
    }

    public RecycleBinDownloadIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void trashedFile_isDownloadable_butOtherReadsStayActiveOnly() throws Exception {
        UploadResponse file = uploadDocument(newFileBuilder());
        deleteFiles(file.id());

        byte[] body = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", file.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult().getResponseBody();
        Assertions.assertArrayEquals(new ClassPathResource("test_file_1.sql").getContentAsByteArray(), body);

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", file.id())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void activeFile_downloadUnchanged() {
        UploadResponse file = uploadDocument(newFileBuilder());

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void purgedFile_isNotDownloadable() {
        UploadResponse file = uploadDocument(newFileBuilder());
        deleteFiles(file.id());

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN)
                .body(BodyInserters.fromValue(new DeleteRequest(List.of(file.id()))))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", file.id())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void restoredFile_isDownloadable() {
        UploadResponse file = uploadDocument(newFileBuilder());
        deleteFiles(file.id());

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/restore")
                .body(BodyInserters.fromValue(new DeleteRequest(List.of(file.id()))))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void trashedFolder_isNotDownloadable() {
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest("bin-download-folder-" + UUID.randomUUID(), null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new DeleteRequest(List.of(folder.id()))))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", folder.id())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void unknownId_isNotFound() {
        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    private void deleteFiles(UUID... ids) {
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(new DeleteRequest(List.of(ids))))
                .exchange()
                .expectStatus().isNoContent();
    }
}
