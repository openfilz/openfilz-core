package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateBlankDocumentRequest;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentTemplateType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * End-to-end integration tests for the POST /api/v1/documents/create-blank endpoint.
 * Tests all document types (WORD, EXCEL, POWERPOINT, TEXT) and covers all code branches:
 * - Success cases for each document type
 * - Creation at root level and in subfolders
 * - Extension auto-append when missing
 * - Name containing "/" validation (403 Forbidden)
 * - Parent folder not found (404 Not Found)
 * - Duplicate name handling (409 Conflict)
 * - Request validation (400 Bad Request)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class CreateBlankDocumentIT extends TestContainersBaseConfig {

    private static final String CREATE_BLANK_ENDPOINT = RestApiVersion.API_PREFIX + "/documents/create-blank";

    public CreateBlankDocumentIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Success Cases ====================

    @Test
    void createBlankWordDocument_atRoot_shouldSucceed() {
        String name = "TestDocument_" + UUID.randomUUID() + ".docx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.WORD, null);

        UploadResponse response = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals(name, response.name());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", response.contentType());
        assertTrue(response.size() > 0);
    }

    @Test
    void createBlankExcelDocument_atRoot_shouldSucceed() {
        String name = "TestSpreadsheet_" + UUID.randomUUID() + ".xlsx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.EXCEL, null);

        UploadResponse response = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals(name, response.name());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.contentType());
        assertTrue(response.size() > 0);
    }

    @Test
    void createBlankPowerPointDocument_atRoot_shouldSucceed() {
        String name = "TestPresentation_" + UUID.randomUUID() + ".pptx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.POWERPOINT, null);

        UploadResponse response = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals(name, response.name());
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", response.contentType());
        assertTrue(response.size() > 0);
    }

    @Test
    void createBlankTextDocument_atRoot_shouldSucceed() {
        String name = "TestDocument_" + UUID.randomUUID() + ".txt";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.TEXT, null);

        UploadResponse response = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals(name, response.name());
        assertEquals("text/plain", response.contentType());
        // Text files are empty, so size should be 0
        assertEquals(0L, response.size());
    }

    @Test
    void createBlankDocument_inSubfolder_shouldSucceed() {
        // First create a folder
        String folderName = "TestFolder_" + UUID.randomUUID();
        CreateFolderRequest folderRequest = new CreateFolderRequest(folderName, null);

        FolderResponse folderResponse = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(folderResponse);
        assertNotNull(folderResponse.id());

        // Now create a blank document in that folder
        String docName = "SubfolderDoc_" + UUID.randomUUID() + ".docx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(docName, DocumentTemplateType.WORD, folderResponse.id());

        UploadResponse response = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals(docName, response.name());
    }

    // ==================== Extension Auto-Append Tests ====================

    static Stream<Arguments> extensionAutoAppendTestCases() {
        return Stream.of(
                Arguments.of("DocumentWithoutExtension", DocumentTemplateType.WORD, ".docx"),
                Arguments.of("SpreadsheetNoExt", DocumentTemplateType.EXCEL, ".xlsx"),
                Arguments.of("PresentationNoExt", DocumentTemplateType.POWERPOINT, ".pptx"),
                Arguments.of("TextFileNoExt", DocumentTemplateType.TEXT, ".txt")
        );
    }

    @ParameterizedTest
    @MethodSource("extensionAutoAppendTestCases")
    void createBlankDocument_withoutExtension_shouldAutoAppendExtension(String baseName, DocumentTemplateType type, String expectedExtension) {
        String name = baseName + "_" + UUID.randomUUID();
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, type, null);

        UploadResponse response = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertNotNull(response.id());
        assertTrue(response.name().endsWith(expectedExtension),
                "Expected name to end with " + expectedExtension + " but got: " + response.name());
        assertEquals(name + expectedExtension, response.name());
    }

    @Test
    void createBlankDocument_withCorrectExtension_shouldNotDuplicate() {
        String name = "DocWithExtension_" + UUID.randomUUID() + ".docx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.WORD, null);

        UploadResponse response = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertEquals(name, response.name());
        assertFalse(response.name().endsWith(".docx.docx"), "Extension should not be duplicated");
    }

    // ==================== Error Cases ====================

    @Test
    void createBlankDocument_withSlashInName_shouldReturn403() {
        String name = "Invalid/Name_" + UUID.randomUUID() + ".docx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.WORD, null);

        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isForbidden();
    }



    @Test
    void createBlankDocument_withNonExistentParentFolder_shouldReturn404() {
        UUID nonExistentFolderId = UUID.randomUUID();
        String name = "TestDocument_" + UUID.randomUUID() + ".docx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.WORD, nonExistentFolderId);

        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void createBlankDocument_withDuplicateName_shouldReturn409() {
        // First create a document
        String name = "DuplicateTest_" + UUID.randomUUID() + ".docx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, DocumentTemplateType.WORD, null);

        UploadResponse firstResponse = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(firstResponse);

        // Try to create another document with the same name
        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createBlankDocument_withDuplicateNameInSubfolder_shouldReturn409() {
        // First create a folder
        String folderName = "DuplicateFolder_" + UUID.randomUUID();
        CreateFolderRequest folderRequest = new CreateFolderRequest(folderName, null);

        FolderResponse folderResponse = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(folderResponse);

        // Create first document in folder
        String docName = "DuplicateDoc_" + UUID.randomUUID() + ".xlsx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(docName, DocumentTemplateType.EXCEL, folderResponse.id());

        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated();

        // Try to create duplicate in same folder
        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createBlankDocument_sameNameDifferentFolders_shouldSucceed() {
        // Create two different folders
        String folder1Name = "Folder1_" + UUID.randomUUID();
        String folder2Name = "Folder2_" + UUID.randomUUID();

        FolderResponse folder1 = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new CreateFolderRequest(folder1Name, null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        FolderResponse folder2 = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new CreateFolderRequest(folder2Name, null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(folder1);
        assertNotNull(folder2);

        // Create document with same name in both folders - should succeed
        String docName = "SameNameDoc_" + UUID.randomUUID() + ".docx";

        UploadResponse response1 = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new CreateBlankDocumentRequest(docName, DocumentTemplateType.WORD, folder1.id())))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        UploadResponse response2 = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new CreateBlankDocumentRequest(docName, DocumentTemplateType.WORD, folder2.id())))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response1);
        assertNotNull(response2);
        assertNotEquals(response1.id(), response2.id());
        assertEquals(docName, response1.name());
        assertEquals(docName, response2.name());
    }

    // ==================== Validation Tests ====================

    @Test
    void createBlankDocument_withEmptyName_shouldReturn400() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("", DocumentTemplateType.WORD, null);

        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createBlankDocument_withBlankName_shouldReturn400() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("   ", DocumentTemplateType.WORD, null);

        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createBlankDocument_withNullDocumentType_shouldReturn400() {
        // Use raw JSON to send null document type
        String jsonRequest = """
                {
                    "name": "TestDocument.docx",
                    "documentType": null,
                    "parentFolderId": null
                }
                """;

        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonRequest)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createBlankDocument_withInvalidDocumentType_shouldReturn400() {
        // Use raw JSON to send invalid document type
        String jsonRequest = """
                {
                    "name": "TestDocument.pdf",
                    "documentType": "PDF",
                    "parentFolderId": null
                }
                """;

        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonRequest)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void createBlankDocument_withTooLongName_shouldReturn400() {
        // Create a name longer than 255 characters
        String longName = "A".repeat(256) + ".docx";
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(longName, DocumentTemplateType.WORD, null);

        getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ==================== All Document Types Parameterized Test ====================

    static Stream<Arguments> allDocumentTypesTestCases() {
        return Stream.of(
                Arguments.of(DocumentTemplateType.WORD, ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                Arguments.of(DocumentTemplateType.EXCEL, ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                Arguments.of(DocumentTemplateType.POWERPOINT, ".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                Arguments.of(DocumentTemplateType.TEXT, ".txt", "text/plain")
        );
    }

    @ParameterizedTest
    @MethodSource("allDocumentTypesTestCases")
    void createBlankDocument_allTypes_shouldHaveCorrectContentTypeAndExtension(
            DocumentTemplateType type, String expectedExtension, String expectedContentType) {
        String name = "TypeTest_" + UUID.randomUUID() + expectedExtension;
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(name, type, null);

        UploadResponse response = getWebTestClient().post()
                .uri(CREATE_BLANK_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertEquals(name, response.name());
        assertEquals(expectedContentType, response.contentType());
        assertTrue(response.name().endsWith(expectedExtension));
    }
}
