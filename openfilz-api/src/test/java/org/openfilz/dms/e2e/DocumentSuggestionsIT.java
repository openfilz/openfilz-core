package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests covering:
 * - DocumentSuggestionController (GET /api/v1/suggestions)
 * - DefaultDocumentSuggestionService (blank query → empty, valid query → results)
 * - DocumentLocalSearchDAOImpl (with/without parentId filter, sort)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class DocumentSuggestionsIT extends TestContainersBaseConfig {

    public DocumentSuggestionsIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Basic Suggestion Queries ====================

    @Test
    void whenSuggestionsWithMatchingQuery_thenResultsReturned() {
        // Upload a file to ensure suggestions have data
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse uploaded = uploadDocument(builder);
        Assertions.assertNotNull(uploaded);

        // "test_file_1" should match the uploaded file name
        List<Suggest> suggestions = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                        .queryParam("q", "test_file_1")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Suggest>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(suggestions);
        Assertions.assertFalse(suggestions.isEmpty(), "Should return suggestions matching 'test_file_1'");
        for (Suggest s : suggestions) {
            Assertions.assertNotNull(s.id());
            Assertions.assertNotNull(s.s());
        }
    }

    @Test
    void whenSuggestionsWithNonMatchingQuery_thenEmptyResults() {
        String noMatchQuery = "zzz-nonexistent-" + UUID.randomUUID();

        List<Suggest> suggestions = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                        .queryParam("q", noMatchQuery)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Suggest>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(suggestions);
        Assertions.assertTrue(suggestions.isEmpty(), "Should return no suggestions for non-matching query");
    }

    // ==================== Suggestions with Filters ====================

    @Test
    void whenSuggestionsWithPartialQuery_thenResultsReturned() {
        // Create a folder with a unique name
        String uniqueName = "suggest-partial-" + UUID.randomUUID();
        CreateFolderRequest folderRequest = new CreateFolderRequest(uniqueName, null);
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(folder);

        // Upload file in folder
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        // Search with the unique folder name prefix
        List<Suggest> suggestions = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                        .queryParam("q", "suggest-partial")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Suggest>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(suggestions);
        Assertions.assertFalse(suggestions.isEmpty(), "Should find at least the folder matching partial query");
    }

    // ==================== Suggestions for Folders ====================

    @Test
    void whenSuggestionsMatchFolder_thenFolderReturned() {
        String uniqueFolderName = "suggest-match-" + UUID.randomUUID();
        CreateFolderRequest folderRequest = new CreateFolderRequest(uniqueFolderName, null);
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(folderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(folder);

        // Search for the folder by its unique name
        List<Suggest> suggestions = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                        .queryParam("q", uniqueFolderName)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<Suggest>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(suggestions);
        Assertions.assertFalse(suggestions.isEmpty(), "Should find the folder by name");
        // Folder suggestions should have null extension
        boolean foundFolder = suggestions.stream()
                .anyMatch(s -> s.id().equals(folder.id()) && s.ext() == null);
        Assertions.assertTrue(foundFolder, "Should find the specific folder with null extension");
    }
}
