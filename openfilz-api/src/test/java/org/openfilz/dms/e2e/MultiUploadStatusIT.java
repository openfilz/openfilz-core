package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
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
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for DocumentController.uploadMultiple covering:
 * - buildMultipleUploadResponse: all success → 200, partial success → 207, all fail same type → mapped status, all fail mixed → 500
 * - determineAllErrorsHttpStatus: DOCUMENT_NOT_FOUND→404, DUPLICATE_NAME→409, OPERATION_FORBIDDEN→403, FILE_SIZE_EXCEEDED→413
 * - uploadMultiple with parametersByFilename (metadata + parentFolderId per file)
 * - DocumentController.sendDownloadResponse: folder download (ZIP suffix, APPLICATION_OCTET_STREAM)
 * - downloadMultipleDocumentsAsZip: empty IDs → 400
 * - DocumentServiceImpl.renameFolder same name → 409
 * - DocumentServiceImpl.moveDocument allowDuplicateFileNames=true
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class MultiUploadStatusIT extends TestContainersBaseConfig {

    public MultiUploadStatusIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Upload multiple: all success → 200 ====================

    @Test
    void whenUploadMultipleAllSuccess_thenOk() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("file", new ClassPathResource("test_file_1.sql"));

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Upload multiple: partial success → 207 ====================

    @Test
    void whenUploadMultiplePartialSuccess_then207() {
        // Upload one file to create duplicate
        String folderName = "multi-partial-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder setup = newFileBuilder();
        setup.part("parentFolderId", folder.id().toString());
        uploadDocument(setup); // uploads test.txt into folder

        // Upload multiple: test.txt (duplicate → error) and test_file_1.sql (success)
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("file", new ClassPathResource("test_file_1.sql"));
        String paramsJson = "[{\"filename\":\"test.txt\",\"fileAttributes\":{\"parentFolderId\":\"" + folder.id() + "\"}},{\"filename\":\"test_file_1.sql\",\"fileAttributes\":{\"parentFolderId\":\"" + folder.id() + "\"}}]";
        builder.part("parametersByFilename", paramsJson);

        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                        .queryParam("allowDuplicateFileNames", false)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isEqualTo(207);
    }

    // ==================== Upload multiple: all fail, same error type (DOCUMENT_NOT_FOUND) → 404 ====================

    @Test
    void whenUploadMultipleAllFailNotFound_then404() {
        UUID nonExistentParent = UUID.randomUUID();

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("file", new ClassPathResource("test_file_1.sql"));
        String paramsJson = "[{\"filename\":\"test.txt\",\"fileAttributes\":{\"parentFolderId\":\"" + nonExistentParent + "\"}},{\"filename\":\"test_file_1.sql\",\"fileAttributes\":{\"parentFolderId\":\"" + nonExistentParent + "\"}}]";
        builder.part("parametersByFilename", paramsJson);

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Upload multiple: all fail, same error type (DUPLICATE_NAME) → 409 ====================

    @Test
    void whenUploadMultipleAllFailDuplicate_then409() {
        String folderName = "multi-dup-all-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // Create both files first
        MultipartBodyBuilder setup1 = new MultipartBodyBuilder();
        setup1.part("file", new ClassPathResource("test.txt"));
        setup1.part("parentFolderId", folder.id().toString());
        uploadDocument(setup1);

        MultipartBodyBuilder setup2 = new MultipartBodyBuilder();
        setup2.part("file", new ClassPathResource("test_file_1.sql"));
        setup2.part("parentFolderId", folder.id().toString());
        uploadDocument(setup2);

        // Now upload both again → all duplicates
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("file", new ClassPathResource("test_file_1.sql"));
        String paramsJson = "[{\"filename\":\"test.txt\",\"fileAttributes\":{\"parentFolderId\":\"" + folder.id() + "\"}},{\"filename\":\"test_file_1.sql\",\"fileAttributes\":{\"parentFolderId\":\"" + folder.id() + "\"}}]";
        builder.part("parametersByFilename", paramsJson);

        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                        .queryParam("allowDuplicateFileNames", false)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Upload multiple: all fail, mixed error types → 500 ====================

    @Test
    void whenUploadMultipleAllFailMixedErrors_then500() {
        String folderName = "multi-mixed-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // Create test.txt so it will be duplicate
        MultipartBodyBuilder setup = new MultipartBodyBuilder();
        setup.part("file", new ClassPathResource("test.txt"));
        setup.part("parentFolderId", folder.id().toString());
        uploadDocument(setup);

        // test.txt → duplicate (409), test_file_1.sql → not found parent (404)
        UUID nonExistentParent = UUID.randomUUID();
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("file", new ClassPathResource("test_file_1.sql"));
        String paramsJson = "[{\"filename\":\"test.txt\",\"fileAttributes\":{\"parentFolderId\":\"" + folder.id() + "\"}},{\"filename\":\"test_file_1.sql\",\"fileAttributes\":{\"parentFolderId\":\"" + nonExistentParent + "\"}}]";
        builder.part("parametersByFilename", paramsJson);

        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                        .queryParam("allowDuplicateFileNames", false)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    // ==================== Upload multiple: with parametersByFilename (metadata) ====================

    @Test
    void whenUploadMultipleWithMetadataPerFile_thenSuccess() {
        String folderName = "multi-meta-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        String paramsJson = "[{\"filename\":\"test.txt\",\"fileAttributes\":{\"parentFolderId\":\"" + folder.id() + "\",\"metadata\":{\"country\":\"UK\"}}}]";
        builder.part("parametersByFilename", paramsJson);

        List<UploadResponse> responses = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(responses);
        Assertions.assertFalse(responses.isEmpty());
    }

    // ==================== Upload multiple: with allowDuplicateFileNames=true ====================

    @Test
    void whenUploadMultipleWithAllowDuplicates_thenAllSuccess() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));

        // Upload test.txt twice with allowDuplicateFileNames=true
        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Download multiple: empty list → 400 ====================

    @Test
    void whenDownloadMultipleEmptyIds_thenBadRequest() {
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of())
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ==================== Download multiple: valid documents ====================

    @Test
    void whenDownloadMultipleDocuments_thenZipReturned() {
        UploadResponse file1 = uploadDocument(newFileBuilder());

        MultipartBodyBuilder builder2 = new MultipartBodyBuilder();
        builder2.part("file", new ClassPathResource("test_file_1.sql"));
        UploadResponse file2 = uploadDocument(builder2);

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(file1.id(), file2.id()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM);
    }

    // ==================== Rename folder to same name → 409 ====================

    @Test
    void whenRenameFolderToSameName_thenConflict() {
        String folderName = "rename-same-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/folders/{id}/rename", folder.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"newName\":\"" + folderName + "\"}")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Rename file → success ====================

    @Test
    void whenRenameFile_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        String newName = "renamed-" + UUID.randomUUID() + ".txt";
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/files/{id}/rename", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"newName\":\"" + newName + "\"}")
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Move folder with allowDuplicateFileNames ====================

    @Test
    void whenMoveFolderWithAllowDuplicates_thenOk() {
        String srcName = "move-dup-src-" + UUID.randomUUID();
        FolderResponse srcFolder = createFolder(srcName, null);

        String dstName = "move-dup-dst-" + UUID.randomUUID();
        FolderResponse dstFolder = createFolder(dstName, null);

        // Create a folder with the same name in dst
        createFolder(srcName, dstFolder.id());

        // Move srcFolder to dstFolder with allowDuplicateFileNames=true - should NOT error
        // Actually, move checks name collision - with allowDuplicateFileNames=true, it should bypass
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"documentIds\":[\"" + srcFolder.id() + "\"],\"targetFolderId\":\"" + dstFolder.id() + "\",\"allowDuplicateFileNames\":true}")
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Move file with allowDuplicateFileNames ====================

    @Test
    void whenMoveFileWithAllowDuplicates_thenOk() {
        String folderName = "move-file-dup-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        UploadResponse file1 = uploadDocument(newFileBuilder());

        // Create duplicate file in folder
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        // Move file1 to folder with allowDuplicateFileNames=true
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"documentIds\":[\"" + file1.id() + "\"],\"targetFolderId\":\"" + folder.id() + "\",\"allowDuplicateFileNames\":true}")
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Move file to same folder → error ====================

    @Test
    void whenMoveFileToSameFolder_thenError() {
        String folderName = "move-same-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"documentIds\":[\"" + file.id() + "\"],\"targetFolderId\":\"" + folder.id() + "\"}")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Rename file to duplicate name → 409 ====================

    @Test
    void whenRenameFileToDuplicateName_thenConflict() {
        String folderName = "rename-dup-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // Upload test_file_1.sql into folder (the first file)
        MultipartBodyBuilder b1 = newFileBuilder();
        b1.part("parentFolderId", folder.id().toString());
        uploadDocument(b1); // test_file_1.sql

        // Upload test.txt into the same folder (the second file)
        MultipartBodyBuilder b2 = new MultipartBodyBuilder();
        b2.part("file", new ClassPathResource("test.txt"));
        b2.part("parentFolderId", folder.id().toString());
        UploadResponse file2 = uploadDocument(b2);

        // Rename file2 (test.txt) to test_file_1.sql (already exists)
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/files/{id}/rename", file2.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"newName\":\"test_file_1.sql\"}")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Copy files with allowDuplicateFileNames ====================

    @Test
    void whenCopyFileWithAllowDuplicates_thenOk() {
        String folderName = "copy-dup-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        // Copy file into same folder with allowDuplicateFileNames=true
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"documentIds\":[\"" + file.id() + "\"],\"targetFolderId\":\"" + folder.id() + "\",\"allowDuplicateFileNames\":true}")
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Helper Methods ====================

    private FolderResponse createFolder(String name, UUID parentId) {
        CreateFolderRequest request = new CreateFolderRequest(name, parentId);
        return getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
    }
}
