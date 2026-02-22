package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateBlankDocumentRequest;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentTemplateType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for blank document creation covering:
 * - DocumentServiceImpl.createBlankDocument() all branches
 * - DocumentServiceImpl.getContentType() switch (WORD, EXCEL, POWERPOINT, TEXT)
 * - DocumentServiceImpl.ensureCorrectExtension() switch branches
 * - Error paths: slash in name (403), non-existent parent (404), duplicate name (409)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class BlankDocumentIT extends TestContainersBaseConfig {

    public BlankDocumentIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Blank Document Types ====================

    @Test
    void whenCreateBlankWordDocument_thenCreated() {
        String name = "blank-word-" + UUID.randomUUID();
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.WORD, null);

        UploadResponse response = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.name().endsWith(".docx"));
        Assertions.assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", response.contentType());
    }

    @Test
    void whenCreateBlankExcelDocument_thenCreated() {
        String name = "blank-excel-" + UUID.randomUUID();
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.EXCEL, null);

        UploadResponse response = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.name().endsWith(".xlsx"));
        Assertions.assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.contentType());
    }

    @Test
    void whenCreateBlankPowerpointDocument_thenCreated() {
        String name = "blank-ppt-" + UUID.randomUUID();
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.POWERPOINT, null);

        UploadResponse response = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.name().endsWith(".pptx"));
        Assertions.assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", response.contentType());
    }

    @Test
    void whenCreateBlankTextDocument_thenCreated() {
        String name = "blank-text-" + UUID.randomUUID();
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.TEXT, null);

        UploadResponse response = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.name().endsWith(".txt"));
        Assertions.assertEquals("text/plain", response.contentType());
    }

    // ==================== Extension already present ====================

    @Test
    void whenCreateBlankWordWithExtension_thenNoDoubleExtension() {
        String name = "already-has-ext-" + UUID.randomUUID() + ".docx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.WORD, null);

        UploadResponse response = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        // Should not have .docx.docx
        Assertions.assertFalse(response.name().endsWith(".docx.docx"));
        Assertions.assertEquals(name, response.name());
    }

    @Test
    void whenCreateBlankExcelWithExtension_thenNoDoubleExtension() {
        String name = "has-ext-" + UUID.randomUUID() + ".xlsx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.EXCEL, null);

        UploadResponse response = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertEquals(name, response.name());
    }

    // ==================== With parent folder ====================

    @Test
    void whenCreateBlankInFolder_thenCreated() {
        String folderName = "blank-parent-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        String name = "blank-in-folder-" + UUID.randomUUID();
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.TEXT, folder.id());

        UploadResponse response = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
    }

    // ==================== Error paths ====================

    @Test
    void whenCreateBlankWithSlashInName_thenForbidden() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("invalid/name", DocumentTemplateType.WORD, null);

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenCreateBlankWithNonExistentParent_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("blank-orphan-" + UUID.randomUUID(), DocumentTemplateType.WORD, nonExistentId);

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenCreateBlankDuplicate_thenConflict() {
        String name = "blank-dup-" + UUID.randomUUID();
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.TEXT, null);

        // First: succeeds
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated();

        // Second: same name → duplicate
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isEqualTo(409);
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
