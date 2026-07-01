package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.PageCriteria;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for the multi content-type filter on folder listing
 * ({@code ListFolderRequest.contentTypes}), matched server-side as a case-insensitive
 * OR of LIKE clauses (see {@code ListFolderCriteria} + {@code SqlUtils.appendLikeAnyCriteria}).
 *
 * <p>Everything is set up and asserted through the public REST (upload) and GraphQL
 * (listFolder / count) APIs — no direct DB access. Each test works in its own folder so
 * counts are deterministic and isolated from other data.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class ContentTypeFilterIT extends TestContainersBaseConfig {

    private static final MediaType MSWORD = MediaType.parseMediaType("application/msword");
    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final String LIST_FOLDER_QUERY = """
            query listFolder($request:ListFolderRequest!) {
                listFolder(request:$request) {
                  id
                  type
                  name
                  contentType
                }
            }
            """.trim();

    private static final String COUNT_QUERY = """
            query count($request:ListFolderRequest) {
                count(request:$request)
            }
            """.trim();

    private HttpGraphQlClient graphQlClient;

    public ContentTypeFilterIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    private HttpGraphQlClient getClient() {
        if (graphQlClient == null) {
            graphQlClient = newGraphQlClient();
        }
        return graphQlClient;
    }

    // ==================== single exact content-type ====================

    @Test
    void whenFilterBySingleExactContentType_thenOnlyThatType() {
        FolderResponse folder = createFolder("ct-exact-" + UUID.randomUUID(), null);
        uploadTypedFile(folder.id(), "pdf-example.pdf", MediaType.APPLICATION_PDF);
        uploadTypedFile(folder.id(), "test-image.png", MediaType.IMAGE_PNG);
        uploadTypedFile(folder.id(), "test.txt", MediaType.TEXT_PLAIN);

        List<Map<String, Object>> items = listFolder(folder.id(), List.of("application/pdf"));

        Assertions.assertEquals(1, items.size(), "Only the PDF should match application/pdf");
        Assertions.assertEquals("application/pdf", items.get(0).get("contentType"));
        Assertions.assertEquals(1L, count(folder.id(), List.of("application/pdf")),
                "count must agree with listFolder");
    }

    // ==================== wildcard prefix ====================

    @Test
    void whenFilterByWildcardPrefix_thenAllMatchingSubtypes() {
        FolderResponse folder = createFolder("ct-wildcard-" + UUID.randomUUID(), null);
        uploadTypedFile(folder.id(), "test-image.png", MediaType.IMAGE_PNG);
        uploadTypedFile(folder.id(), "test-image.jpg", MediaType.IMAGE_JPEG);
        uploadTypedFile(folder.id(), "pdf-example.pdf", MediaType.APPLICATION_PDF);

        List<Map<String, Object>> items = listFolder(folder.id(), List.of("image/%"));

        Assertions.assertEquals(2, items.size(), "image/% must match png and jpeg but not the pdf");
        items.forEach(item -> Assertions.assertTrue(
                ((String) item.get("contentType")).startsWith("image/"),
                "unexpected content-type: " + item.get("contentType")));
        Assertions.assertEquals(2L, count(folder.id(), List.of("image/%")));
    }

    // ==================== multiple patterns OR-ed (a file-type category) ====================

    @Test
    void whenFilterByMultipleContentTypes_thenUnionMatches() {
        // The "Word" category spans the legacy (application/msword) and OOXML content-types,
        // which share no single prefix — this is the case the OR-LIKE list exists for.
        FolderResponse folder = createFolder("ct-word-" + UUID.randomUUID(), null);
        uploadTypedFile(folder.id(), "test-document.docx", DOCX);
        uploadTypedFile(folder.id(), "legacy.doc", MSWORD);
        uploadTypedFile(folder.id(), "test-spreadsheet.xlsx", XLSX);

        List<String> wordCategory = List.of(
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        List<Map<String, Object>> items = listFolder(folder.id(), wordCategory);

        Assertions.assertEquals(2, items.size(), "both the .doc and the .docx must match, not the .xlsx");
        Assertions.assertEquals(2L, count(folder.id(), wordCategory));
    }

    @Test
    void whenFilterByMixedExactAndWildcard_thenUnionMatches() {
        FolderResponse folder = createFolder("ct-mixed-" + UUID.randomUUID(), null);
        uploadTypedFile(folder.id(), "pdf-example.pdf", MediaType.APPLICATION_PDF);
        uploadTypedFile(folder.id(), "test-image.png", MediaType.IMAGE_PNG);
        uploadTypedFile(folder.id(), "test.txt", MediaType.TEXT_PLAIN);

        List<Map<String, Object>> items = listFolder(folder.id(), List.of("application/pdf", "image/%"));

        Assertions.assertEquals(2, items.size(), "pdf + image/% must match the pdf and the png, not the text file");
        Assertions.assertEquals(2L, count(folder.id(), List.of("application/pdf", "image/%")));
    }

    // ==================== case-insensitivity ====================

    @Test
    void whenFilterContentTypeCaseInsensitive_thenStillMatches() {
        FolderResponse folder = createFolder("ct-case-" + UUID.randomUUID(), null);
        uploadTypedFile(folder.id(), "pdf-example.pdf", MediaType.APPLICATION_PDF);

        Assertions.assertEquals(1, listFolder(folder.id(), List.of("APPLICATION/PDF")).size(),
                "matching must be case-insensitive");
        Assertions.assertEquals(1L, count(folder.id(), List.of("Application/Pdf")));
    }

    // ==================== no match ====================

    @Test
    void whenNoContentTypeMatches_thenEmptyAndCountZero() {
        FolderResponse folder = createFolder("ct-none-" + UUID.randomUUID(), null);
        uploadTypedFile(folder.id(), "pdf-example.pdf", MediaType.APPLICATION_PDF);

        Assertions.assertTrue(listFolder(folder.id(), List.of("video/%")).isEmpty(),
                "no video in this folder");
        Assertions.assertEquals(0L, count(folder.id(), List.of("video/%")));
    }

    // ==================== helpers ====================

    private void uploadTypedFile(UUID folderId, String filename, MediaType contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("pdf-example.pdf"))
                .filename(filename)
                .contentType(contentType);
        builder.part("parentFolderId", folderId.toString());
        uploadDocument(builder);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listFolder(UUID folderId, List<String> contentTypes) {
        ListFolderRequest request = new ListFolderRequest(
                folderId, null, null, contentTypes, null, null, null, null, null, null, null, null,
                null, null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100), null);
        ClientGraphQlResponse doc = getClient().document(LIST_FOLDER_QUERY)
                .variable("request", request).execute().block();
        Assertions.assertNotNull(doc);
        Assertions.assertTrue(doc.getErrors().isEmpty(), () -> "GraphQL errors: " + doc.getErrors());
        return (List<Map<String, Object>>) ((Map<String, Object>) doc.getData()).get("listFolder");
    }

    @SuppressWarnings("unchecked")
    private long count(UUID folderId, List<String> contentTypes) {
        ListFolderRequest request = new ListFolderRequest(
                folderId, null, null, contentTypes, null, null, null, null, null, null, null, null,
                null, null, null, true, null, null);
        ClientGraphQlResponse doc = getClient().document(COUNT_QUERY)
                .variable("request", request).execute().block();
        Assertions.assertNotNull(doc);
        Assertions.assertTrue(doc.getErrors().isEmpty(), () -> "GraphQL errors: " + doc.getErrors());
        return ((Integer) ((Map<String, Object>) doc.getData()).get("count")).longValue();
    }

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
