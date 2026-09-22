package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.PageCriteria;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for {@code listFolderPosition} / {@code listAllFolderPosition} / {@code countAllFolder}:
 * the index of a document in a filtered + sorted listing, which lets a client (the web file viewer)
 * page through e.g. only the images of a folder, starting from the one the user opened.
 *
 * <p>Set up and asserted through the REST (folders, upload, favorites) and GraphQL APIs only.
 * Each test works in its own folder / under its own name prefix so results are isolated.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class ListingPositionIT extends TestContainersBaseConfig {

    private static final List<String> IMAGES = List.of("image/%");

    private static final String LIST_FOLDER_QUERY = """
            query listFolder($request:ListFolderRequest!) {
                listFolder(request:$request) { id name }
            }
            """.trim();

    private static final String LIST_FOLDER_POSITION_QUERY = """
            query listFolderPosition($request:ListFolderRequest!, $documentId:UUID!) {
                listFolderPosition(request:$request, documentId:$documentId)
            }
            """.trim();

    private static final String LIST_ALL_FOLDER_QUERY = """
            query listAllFolder($request:ListFolderRequest!) {
                listAllFolder(request:$request) { id name }
            }
            """.trim();

    private static final String LIST_ALL_FOLDER_POSITION_QUERY = """
            query listAllFolderPosition($request:ListFolderRequest!, $documentId:UUID!) {
                listAllFolderPosition(request:$request, documentId:$documentId)
            }
            """.trim();

    private static final String COUNT_ALL_FOLDER_QUERY = """
            query countAllFolder($request:ListFolderRequest) {
                countAllFolder(request:$request)
            }
            """.trim();

    private HttpGraphQlClient graphQlClient;

    public ListingPositionIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    private HttpGraphQlClient getClient() {
        if (graphQlClient == null) {
            graphQlClient = newGraphQlClient();
        }
        return graphQlClient;
    }

    @Test
    void whenImagesFiltered_thenPositionIsTheIndexAmongImagesOnly() {
        FolderResponse folder = createFolder("pos-images-" + UUID.randomUUID(), null);
        createFolder("a-subfolder", folder.id());
        UUID a = upload(folder.id(), "a.png", MediaType.IMAGE_PNG);
        UUID b = upload(folder.id(), "b.pdf", MediaType.APPLICATION_PDF);
        UUID c = upload(folder.id(), "c.jpg", MediaType.IMAGE_JPEG);
        upload(folder.id(), "d.txt", MediaType.TEXT_PLAIN);
        UUID e = upload(folder.id(), "e.png", MediaType.IMAGE_PNG);

        ListFolderRequest imagesByName = folderRequest(folder.id(), IMAGES, "name", SortOrder.ASC, 1, 1);
        Assertions.assertEquals(0L, folderPosition(imagesByName, a));
        Assertions.assertEquals(1L, folderPosition(imagesByName, c), "the pdf before c.jpg is not an image");
        Assertions.assertEquals(2L, folderPosition(imagesByName, e));
        Assertions.assertNull(folderPosition(imagesByName, b), "a pdf is not in the images listing");

        ListFolderRequest imagesByNameDesc = folderRequest(folder.id(), IMAGES, "name", SortOrder.DESC, 1, 1);
        Assertions.assertEquals(0L, folderPosition(imagesByNameDesc, e));
        Assertions.assertEquals(2L, folderPosition(imagesByNameDesc, a));

        // Unfiltered: the sub-folder comes first, then every file by name
        ListFolderRequest everything = folderRequest(folder.id(), null, "name", SortOrder.ASC, 1, 1);
        Assertions.assertEquals(3L, folderPosition(everything, c));
    }

    @Test
    void whenSortTies_thenPagesAndPositionsAgree() {
        // Same content → same size: sorting by size is one big tie, broken by the id.
        FolderResponse folder = createFolder("pos-ties-" + UUID.randomUUID(), null);
        for (int i = 0; i < 5; i++) {
            upload(folder.id(), "img-" + i + ".png", MediaType.IMAGE_PNG);
        }

        List<Map<String, Object>> all = listFolder(folderRequest(folder.id(), IMAGES, "size", SortOrder.ASC, 1, 100));
        List<Map<String, Object>> paged = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            paged.addAll(listFolder(folderRequest(folder.id(), IMAGES, "size", SortOrder.ASC, page, 2)));
        }
        Assertions.assertEquals(ids(all), ids(paged), "pages of 2 must add up to the full listing, same order");

        ListFolderRequest request = folderRequest(folder.id(), IMAGES, "size", SortOrder.ASC, 1, 1);
        for (int i = 0; i < all.size(); i++) {
            Assertions.assertEquals((long) i, folderPosition(request, UUID.fromString((String) all.get(i).get("id"))));
        }
    }

    @Test
    void whenFavoriteImages_thenCountAndPositionAcrossFolders() {
        String prefix = "posfav-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        FolderResponse one = createFolder(prefix + "one", null);
        FolderResponse two = createFolder(prefix + "two", null);
        UUID a = upload(one.id(), prefix + "a.png", MediaType.IMAGE_PNG);
        UUID b = upload(two.id(), prefix + "b.pdf", MediaType.APPLICATION_PDF);
        UUID c = upload(two.id(), prefix + "c.png", MediaType.IMAGE_PNG);
        UUID notFavorite = upload(one.id(), prefix + "d.png", MediaType.IMAGE_PNG);
        for (UUID id : List.of(a, b, c)) {
            addFavorite(id);
        }

        ListFolderRequest favoriteImages = allRequest(prefix + "%", IMAGES, true, 1, 1);
        Assertions.assertEquals(2L, countAll(allRequest(prefix + "%", IMAGES, true, null, null)));
        Assertions.assertEquals(0L, allPosition(favoriteImages, a));
        Assertions.assertEquals(1L, allPosition(favoriteImages, c));
        Assertions.assertNull(allPosition(favoriteImages, b), "a pdf is not in the images listing");
        Assertions.assertNull(allPosition(favoriteImages, notFavorite), "not a favorite");

        // Same order as the listing itself
        List<Map<String, Object>> listed = listAll(allRequest(prefix + "%", IMAGES, true, 1, 100));
        Assertions.assertEquals(List.of(a.toString(), c.toString()), ids(listed));

        // Without the favorite filter every matching image counts, across both folders
        Assertions.assertEquals(3L, countAll(allRequest(prefix + "%", IMAGES, null, null, null)));
        Assertions.assertEquals(1L, allPosition(allRequest(prefix + "%", IMAGES, null, 1, 1), c));
    }

    // ==================== helpers ====================

    private ListFolderRequest folderRequest(UUID folderId, List<String> contentTypes, String sortBy, SortOrder order,
                                            int page, int size) {
        return new ListFolderRequest(
                folderId, null, null, contentTypes, null, null, null, null, null, null, null, null,
                null, null, null, true, new PageCriteria(sortBy, order, page, size), null);
    }

    private ListFolderRequest allRequest(String nameLike, List<String> contentTypes, Boolean favorite,
                                         Integer page, Integer size) {
        PageCriteria pageInfo = page == null ? null : new PageCriteria("name", SortOrder.ASC, page, size);
        return new ListFolderRequest(
                null, null, null, contentTypes, null, nameLike, null, null, null, null, null, null,
                null, null, favorite, true, pageInfo, null);
    }

    private List<Map<String, Object>> listFolder(ListFolderRequest request) {
        return list(LIST_FOLDER_QUERY, "listFolder", request);
    }

    private List<Map<String, Object>> listAll(ListFolderRequest request) {
        return list(LIST_ALL_FOLDER_QUERY, "listAllFolder", request);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String query, String field, ListFolderRequest request) {
        ClientGraphQlResponse doc = getClient().document(query).variable("request", request).execute().block();
        assertNoErrors(doc);
        return (List<Map<String, Object>>) ((Map<String, Object>) doc.getData()).get(field);
    }

    private Long folderPosition(ListFolderRequest request, UUID documentId) {
        return position(LIST_FOLDER_POSITION_QUERY, "listFolderPosition", request, documentId);
    }

    private Long allPosition(ListFolderRequest request, UUID documentId) {
        return position(LIST_ALL_FOLDER_POSITION_QUERY, "listAllFolderPosition", request, documentId);
    }

    @SuppressWarnings("unchecked")
    private Long position(String query, String field, ListFolderRequest request, UUID documentId) {
        ClientGraphQlResponse doc = getClient().document(query)
                .variable("request", request).variable("documentId", documentId).execute().block();
        assertNoErrors(doc);
        Number position = (Number) ((Map<String, Object>) doc.getData()).get(field);
        return position == null ? null : position.longValue();
    }

    @SuppressWarnings("unchecked")
    private long countAll(ListFolderRequest request) {
        ClientGraphQlResponse doc = getClient().document(COUNT_ALL_FOLDER_QUERY)
                .variable("request", request).execute().block();
        assertNoErrors(doc);
        return ((Number) ((Map<String, Object>) doc.getData()).get("countAllFolder")).longValue();
    }

    private static void assertNoErrors(ClientGraphQlResponse doc) {
        Assertions.assertNotNull(doc);
        Assertions.assertTrue(doc.getErrors().isEmpty(), () -> "GraphQL errors: " + doc.getErrors());
    }

    private static List<String> ids(List<Map<String, Object>> items) {
        return items.stream().map(i -> (String) i.get("id")).toList();
    }

    private UUID upload(UUID folderId, String filename, MediaType contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("pdf-example.pdf"))
                .filename(filename)
                .contentType(contentType);
        builder.part("parentFolderId", folderId.toString());
        UploadResponse response = uploadDocument(builder);
        Assertions.assertNotNull(response);
        return response.id();
    }

    private void addFavorite(UUID documentId) {
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/favorites/{id}", documentId)
                .exchange()
                .expectStatus().isOk();
    }

    private FolderResponse createFolder(String name, UUID parentId) {
        return getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest(name, parentId)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
    }
}
