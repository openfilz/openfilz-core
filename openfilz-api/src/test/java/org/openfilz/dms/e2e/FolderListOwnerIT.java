package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * {@code GET /folders/list} names each element's owner (the e-mail of its creator), at the root and
 * inside a folder, the same value the GraphQL {@code createdBy} field gives. A client that created a
 * folder and lost its id (an interrupted call) can then find it again by name and tell its own folder
 * from one somebody else made with the same name.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class FolderListOwnerIT extends TestContainersBaseConfig {

    private static final ParameterizedTypeReference<List<FolderElementInfo>> LIST = new ParameterizedTypeReference<>() {};

    public FolderListOwnerIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void folderListNamesTheOwnerAtTheRootAndInsideAFolder() {
        String name = "owner-it-" + UUID.randomUUID();
        FolderResponse folder = createFolder(name, null);
        FolderResponse child = createFolder("child", folder.id());
        MultipartBodyBuilder file = newFileBuilder();
        file.part("parentFolderId", folder.id().toString());
        UploadResponse upload = uploadDocument(file);

        String createdBy = createdBy(folder.id());
        Assertions.assertNotNull(createdBy, "GraphQL gives the creator");

        FolderElementInfo atRoot = list(null, "onlyFolders").stream()
                .filter(e -> e.id().equals(folder.id())).findFirst().orElseThrow();
        Assertions.assertEquals(name, atRoot.name());
        Assertions.assertEquals(createdBy, atRoot.owner(), "the root listing names the owner");

        List<FolderElementInfo> inside = list(folder.id(), null);
        Assertions.assertEquals(2, inside.size());
        Assertions.assertTrue(inside.stream().anyMatch(e -> e.id().equals(child.id())));
        Assertions.assertTrue(inside.stream().anyMatch(e -> e.id().equals(upload.id())));
        Assertions.assertTrue(inside.stream().allMatch(e -> createdBy.equals(e.owner())), "a folder's listing names the owners too");
    }

    private FolderResponse createFolder(String name, UUID parentId) {
        return getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new CreateFolderRequest(name, parentId)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
    }

    private List<FolderElementInfo> list(UUID folderId, String flag) {
        return getWebTestClient().get().uri(uri -> {
                    uri.path(RestApiVersion.API_PREFIX + "/folders/list");
                    if (folderId != null) {
                        uri.queryParam("folderId", folderId);
                    }
                    if (flag != null) {
                        uri.queryParam(flag, true);
                    }
                    return uri.build();
                })
                .exchange()
                .expectStatus().isOk()
                .expectBody(LIST)
                .returnResult().getResponseBody();
    }

    private String createdBy(UUID id) {
        ClientGraphQlResponse response = newGraphQlClient()
                .document("query documentById($id:UUID!) { documentById(id:$id) { id createdBy } }")
                .variable("id", id)
                .execute()
                .block();
        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.getErrors().isEmpty(), () -> String.valueOf(response.getErrors()));
        return response.field("documentById.createdBy").getValue();
    }
}
