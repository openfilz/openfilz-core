package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.SearchByMetadataRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E test for searching documents by metadata.
 *
 * Scenario:
 * 1. Create a folder named "Request"
 * 2. Upload "test1.txt" into this folder (no metadata)
 * 3. Upload "test2.txt" into this folder with metadata: { "type": "Contract" }
 * 4. Search using metadata filter { "type": "Contract" }
 * 5. Assert only test2.txt is returned
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class MetadataSearchIT extends TestContainersBaseConfig {

    public MetadataSearchIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @Test
    void whenSearchByMetadata_thenOnlyMatchingDocumentReturned() {
        // 1. Create a folder named "Request"
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("Request", null);
        FolderResponse folder = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(folder);

        // 2. Upload test1.txt into the folder (no metadata)
        MultipartBodyBuilder builder1 = newFileBuilder("test1.txt");
        builder1.part("parentFolderId", folder.id().toString());
        UploadResponse file1 = getUploadResponse(builder1);
        Assertions.assertNotNull(file1);
        Assertions.assertEquals("test1.txt", file1.name());

        // 3. Upload test2.txt into the folder with metadata { "type": "Contract" }
        MultipartBodyBuilder builder2 = newFileBuilder("test2.txt");
        builder2.part("parentFolderId", folder.id().toString());
        builder2.part("metadata", Map.of("type", "Contract"));
        UploadResponse file2 = getUploadResponse(builder2);
        Assertions.assertNotNull(file2);
        Assertions.assertEquals("test2.txt", file2.name());

        // 4. Search by metadata: { "type": "Contract" }
        SearchByMetadataRequest searchRequest = new SearchByMetadataRequest(
                null,                          // name: search all names
                DocumentType.FILE,             // type: files only
                folder.id(),                   // parentFolderId: search in "Request" folder
                null,                          // rootOnly: not applicable
                Map.of("type", "Contract")     // metadataCriteria
        );

        List<UUID> matchingIds = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(searchRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {})
                .returnResult().getResponseBody();

        // 5. Assert only test2.txt is returned
        Assertions.assertNotNull(matchingIds);
        Assertions.assertEquals(1, matchingIds.size(), "Expected exactly 1 document matching metadata { type: Contract }");
        Assertions.assertEquals(file2.id(), matchingIds.getFirst(), "The matching document should be test2.txt");

        // Cleanup
        DeleteRequest deleteFiles = new DeleteRequest(List.of(file1.id(), file2.id()));
        getWebTestClient().method(HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteFiles))
                .exchange()
                .expectStatus().isNoContent();

        DeleteRequest deleteFolder = new DeleteRequest(List.of(folder.id()));
        getWebTestClient().method(HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteFolder))
                .exchange()
                .expectStatus().isNoContent();
    }
}
