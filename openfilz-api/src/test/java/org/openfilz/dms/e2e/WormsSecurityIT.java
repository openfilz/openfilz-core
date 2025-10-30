package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class WormsSecurityIT extends SecurityIT {

    public WormsSecurityIT(WebTestClient webTestClient) {
        super(webTestClient);
    }

    @DynamicPropertySource
    static void registerResourceServerIssuerProperty(DynamicPropertyRegistry registry) {
        registry.add("openfilz.security.worm-mode", () -> Boolean.TRUE);
        registry.add("openfilz.calculate-checksum", () -> Boolean.TRUE);
    }

    @Test
    void testRenameFile() {
        UploadResponse uploadResponse = uploadNewFile();

        addAuthorization(getRenameFileRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFileRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFileRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFileRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFileRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFileRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isForbidden();
    }

    @Test
    void testPatchMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));
        UploadResponse uploadResponse = newFile(builder);
        addAuthorization(getPatchMetadataRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getPatchMetadataRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getPatchMetadataRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getPatchMetadataRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getPatchMetadataRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getPatchMetadataRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isForbidden();
    }

    @Test
    void testReplaceMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));

        UploadResponse originalUploadResponse = newFile(builder);

        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), adminAccessToken).exchange().expectStatus().isForbidden();

    }

    @Test
    void testDeleteFolder() {

        FolderResponse folder = createFolder();

        addAuthorization(getDeleteFolderRequest(folder), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFolderRequest(folder), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFolderRequest(folder), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFolderRequest(folder), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFolderRequest(folder), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFolderRequest(createFolder()), adminAccessToken).exchange().expectStatus().isForbidden();
    }

    @Test
    void testReplaceContent() {
        UploadResponse originalUploadResponse = uploadNewFile();
        Assertions.assertNotNull(originalUploadResponse);
        addAuthorization(getReplaceContentRequest(originalUploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), adminAccessToken).exchange().expectStatus().isForbidden();
    }

    @Test
    void testDeleteMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));
        UploadResponse uploadResponse = newFile(builder);
        addAuthorization(getDeleteMetadataRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isForbidden();
    }

    @Test
    void testMoveFile() {
        UploadResponse uploadResponse = uploadNewFile();

        FolderResponse folder = createFolder();

        addAuthorization(getMoveFileRequest(uploadResponse, folder), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFileRequest(uploadResponse, folder), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFileRequest(uploadResponse, folder), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFileRequest(uploadResponse, folder), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFileRequest(uploadResponse, folder), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFileRequest(uploadResponse, createFolder()), adminAccessToken).exchange().expectStatus().isForbidden();
    }

    @Test
    void testRenameFolder() {

        FolderResponse folder = createFolder();

        addAuthorization(getRenameFolderRequest(folder), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFolderRequest(folder), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFolderRequest(folder), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFolderRequest(folder), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFolderRequest(folder), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFolderRequest(folder), adminAccessToken).exchange().expectStatus().isForbidden();
    }

    @Test
    void testDeleteFile() {
        UploadResponse uploadResponse = uploadNewFile();

        addAuthorization(getDeleteFileRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFileRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFileRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFileRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFileRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFileRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isForbidden();
    }

    @Test
    void testMoveFolder() {

        FolderResponse folder1 = createFolder();
        FolderResponse folder2 = createFolder();

        addAuthorization(getMoveFolderRequest(folder1, folder2), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFolderRequest(folder1, folder2), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFolderRequest(folder1, folder2), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFolderRequest(folder1, folder2), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFolderRequest(folder1, folder2), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFolderRequest(folder1, createFolder()), adminAccessToken).exchange().expectStatus().isForbidden();
    }

}
