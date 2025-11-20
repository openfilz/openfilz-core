package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.FavoriteRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.PageCriteria;
import org.openfilz.dms.dto.response.DashboardStatisticsResponse;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class FavoritesIT extends TestContainersBaseConfig {

    protected HttpGraphQlClient graphQlHttpClient;

    public FavoritesIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    private HttpGraphQlClient getGraphQlHttpClient() {
        if(graphQlHttpClient == null) {
            graphQlHttpClient = newGraphQlClient();
        }
        return graphQlHttpClient;
    }


    @Test
    void testFavorites() {
        RestoreHandler folderAndFile1 = createFolderAndFile("test-restore-folder-1", null);
        RestoreHandler folderAndFile2 = createFolderAndFile("test-restore-folder-2", null);
        RestoreHandler folderAndFile1_1 = createFolderAndFile("test-restore-folder-1-1", folderAndFile1.parent().id());
        RestoreHandler folderAndFile2_1 = createFolderAndFile("test-restore-folder-2-1", folderAndFile2.parent().id());
        RestoreHandler folderAndFile1_2 = createFolderAndFile("test-restore-folder-1-2", folderAndFile1.parent().id());
        RestoreHandler folderAndFile2_2 = createFolderAndFile("test-restore-folder-2-2", folderAndFile2.parent().id());
        RestoreHandler folderAndFile1_1_1 = createFolderAndFile("test-restore-folder-1-1-1", folderAndFile1_1.parent().id());
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse rootFile = uploadDocument(builder);

        builder = newFileBuilder();
        builder.part("parentFolderId", folderAndFile1_1_1.parent().id().toString());
        UploadResponse file1_1_1_2 = uploadDocument(builder);

        getWebTestClient().method(HttpMethod.POST).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}", folderAndFile1.file.id())
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().method(HttpMethod.GET).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}/is-favorite", folderAndFile1.file.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .isEqualTo(Boolean.TRUE);

        getWebTestClient().method(HttpMethod.PUT).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}/toggle", folderAndFile1.file.id())
                .exchange()
                .expectStatus().isOk().expectBody(Boolean.class)
                .isEqualTo(Boolean.FALSE);

        getWebTestClient().method(HttpMethod.GET).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}/is-favorite", folderAndFile1.file.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .isEqualTo(Boolean.FALSE);

        getWebTestClient().method(HttpMethod.PUT).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}/toggle", folderAndFile1.file.id())
                .exchange()
                .expectStatus().isOk().expectBody(Boolean.class)
                .isEqualTo(Boolean.TRUE);

        getWebTestClient().method(HttpMethod.GET).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}/is-favorite", folderAndFile1.file.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .isEqualTo(Boolean.TRUE);

        getWebTestClient().method(HttpMethod.PUT).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}/toggle", folderAndFile2.file.id())
                .exchange()
                .expectStatus().isOk().expectBody(Boolean.class)
                .isEqualTo(Boolean.TRUE);

        getWebTestClient().method(HttpMethod.GET).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}/is-favorite", folderAndFile2.file.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .isEqualTo(Boolean.TRUE);

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}", folderAndFile2.file.id())
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().method(HttpMethod.GET).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES + "/{id}/is-favorite", folderAndFile2.file.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .isEqualTo(Boolean.FALSE);

        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();


        Mono<ClientGraphQlResponse> countGraphQl;
        FavoriteRequest request;



        request = new FavoriteRequest(null, null, null, null, null, null, null, null, null, null, null
                , null, new PageCriteria(null, null, 1, 100));
        var graphQlRequest = """
                query listFavorites($request:FavoriteRequest!) {
                    listFavorites(request:$request) {
                      id
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> checkListFoldersReturnedSize(doc, 1))
                .expectComplete()
                .verify();
        graphQlRequest = """
                query countFavorites($request:FavoriteRequest) {
                    countFavorites(request:$request)
                }
                """.trim();
        request = new FavoriteRequest(null, null, null, null, null, null, null, null, null, null, null
                , null, null);

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountFavoritesIsOK(doc, 1L))
                .expectComplete()
                .verify();

        ListFolderRequest listFolderRequest = new ListFolderRequest(null, null, null, null, null, null, null, null, null, null, null, null
                , null, false, true, null);
        graphQlRequest = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",listFolderRequest)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 3L))
                .expectComplete()
                .verify();

        listFolderRequest = new ListFolderRequest(null, null, null, null, null, null, null, null, null, null, null, null
                , null, true, true, null);
        graphQlRequest = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",listFolderRequest)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 0L))
                .expectComplete()
                .verify();

        listFolderRequest = new ListFolderRequest(null, null, null, null, null, null, null, null, null, null, null, null
                , null, null, true, null);
        graphQlRequest = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",listFolderRequest)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 3L))
                .expectComplete()
                .verify();

        listFolderRequest = new ListFolderRequest(folderAndFile1.parent.id(), null, null, null, null, null, null, null, null, null, null, null
                , null, true, true, null);
        graphQlRequest = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",listFolderRequest)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 1L))
                .expectComplete()
                .verify();

    }

    protected boolean checkCountFavoritesIsOK(ClientGraphQlResponse doc, Long expectedCount) {
        return Objects.equals(((Integer) ((Map<String, Object>) doc.getData()).get("countFavorites")).longValue(), expectedCount);
    }

    private boolean checkListFoldersReturnedSize(ClientGraphQlResponse doc, int expectedSize) {
        return ((List<Map<String, Object>>) ((Map<String, Map<String, Object>>) doc.getData()).get("listFavorites")).size() == expectedSize;
    }



    record RestoreHandler(FolderResponse parent, UploadResponse file) {}


    private RestoreHandler createFolderAndFile(String name, UUID parentId) {
        CreateFolderRequest rootFolder1 = new CreateFolderRequest(name, parentId);


        FolderResponse rootFolder1Response = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(rootFolder1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", rootFolder1Response.id().toString());

        UploadResponse file1_1 = uploadDocument(builder);

        return new RestoreHandler(rootFolder1Response, file1_1);
    }



}
