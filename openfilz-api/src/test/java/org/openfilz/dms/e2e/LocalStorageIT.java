package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.GraphQlQueryConfig;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.openfilz.dms.enums.AuditAction.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class LocalStorageIT extends TestContainersBaseConfig {

    protected String username = "anonymousUser";

    protected String test_file_1_sql_sha = "36be9faa01295f0416c52234e8c03d77f6b16294173fc64e08f94fb1df2104fc";

    protected String test_txt_sha = "662ce40a19604fae1a36dc9737598eb0e3b81a11e70aff50f190b6174ca72658";

    
    @Autowired
    protected DatabaseClient databaseClient;

    protected HttpGraphQlClient graphQlHttpClient;

    private static final Long testTxtSize;

    static {
        try {
             testTxtSize = (long) ClassLoader.getSystemResource("test.txt").openConnection().getContentLength();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public LocalStorageIT(WebTestClient webTestClient) {
        super(webTestClient);
    }

    protected String getUsername() {
        return username;
    }


    @Test
    protected void whenCountElements_thenKO() {

        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        //create post
        ListFolderRequest request = new ListFolderRequest(null, null, null, null, null, null, null, null, null, null, null, null
                , null, new PageCriteria(null, null,1,10 ));
        var graphQlRequest = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();

        Mono<ClientGraphQlResponse> countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc->!doc.getErrors().isEmpty())
                .expectComplete()
                .verify();
    }


    @Test
    void whenCountElements_thenOK() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder-Count"+UUID.randomUUID(), null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folderResponse.id().toString());
        UploadResponse response = getUploadResponse(builder);

        Long count = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/folders/count")
                                .queryParam("folderId", folderResponse.id().toString())
                                .build())
                .exchange()
                .expectBody(Long.class)
                .returnResult().getResponseBody();
        Assertions.assertEquals(1L, count);

        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        //create post
        ListFolderRequest request = new ListFolderRequest(folderResponse.id(), null, null, null, null, null, null, null, null, null, null, null
                , null, null);
        var graphQlRequest = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();

        Mono<ClientGraphQlResponse> countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 1L))
                .expectComplete()
                .verify();



        builder = newFileBuilder();

        getUploadDocumentExchange(builder).expectStatus().isCreated();

        count = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/count")
                .exchange()
                .expectBody(Long.class)
                .returnResult().getResponseBody();
        Assertions.assertTrue(count > 0);

        request = new ListFolderRequest(null, null, null, null, null, null, null, null, null, null, null, null
                , null, null);

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        StepVerifier.create(countGraphQl)
                .expectNextMatches(this::checkCountIsGreaterThanZero)
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderGraphQlNoPaging_thenError() {
        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        //create post
        ListFolderRequest request = new ListFolderRequest(null, null, null, null, null, null, null, null, null, null, null, null
                , null, null);
        var graphQlRequest = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      contentType
                      type
                      name
                      metadata
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc->!doc.getErrors().isEmpty())
                .expectComplete()
                .verify();

        request = new ListFolderRequest(null, null, null, "toto", "tutu", null, null, null, null, null, null, null
                , null, new PageCriteria("name", SortOrder.ASC, 1, 100));

        graphQlRequest = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      contentType
                      type
                      name
                      metadata
                    }
                }
                """.trim();

        response = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc->!doc.getErrors().isEmpty())
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderEmptyGraphQl_thenOK() {
        UUID uuid0 = UUID.randomUUID();
        ListFolderRequest request = new ListFolderRequest(null, DocumentType.FOLDER, null, null, null, Map.of("testId", uuid0.toString()), null, null, null, null, null, null
                , null, new PageCriteria(null, null, 1, 100));
        String graphQlRequest = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      contentType
                      type
                      name
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
                    }
                }
                """.trim();
        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        Mono<ClientGraphQlResponse> response = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> checkListFoldersReturnedSize(doc, 0))
                .expectComplete()
                .verify();
    }

    @Test
    void whenGetDocByIdGraphQl_thenOK() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-docById"+UUID.randomUUID(), null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folderResponse.id().toString());
        UploadResponse uploadedFile = getUploadResponse(builder);

        String graphQlRequest = """
                query documentById($id:UUID!) {
                    documentById(id:$id) {
                      id
                      parentId
                      contentType
                      type
                      name
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
                    }
                }
                """.trim();
        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        Mono<ClientGraphQlResponse> response = httpGraphQlClient
                .document(graphQlRequest)
                .variable("id",uploadedFile.id())
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> checkDocById(doc, uploadedFile))
                .expectComplete()
                .verify();
    }

    private boolean checkDocById(ClientGraphQlResponse doc, UploadResponse uploadedFile) {
        Map<String, Object> items = ((Map<String, Map<String, Object>>) doc.getData()).get(GraphQlQueryConfig.DOCUMENT_BY_ID);
        return items.get("id").equals(uploadedFile.id().toString()) && items.get("name").equals(uploadedFile.name()) && items.get("contentType").equals(uploadedFile.contentType()) && Long.valueOf(items.get("size").toString()).equals(uploadedFile.size());
    }

    @Test
    void whenListNewFolderGraphQl_thenOK() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder-graphQl"+UUID.randomUUID(), null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder("test_file_1.sql", "test.txt");


        UUID uuid0 = UUID.randomUUID();

        UUID uuid1 = UUID.randomUUID();
        Map<String, Object> metadata1 = Map.of("testId", uuid0.toString(), "appId", uuid1.toString());

        UUID uuid2 = UUID.randomUUID();
        Map<String, Object> metadata2 = Map.of("testId", uuid0.toString(), "appId", uuid2.toString());

        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter("test_file_1.sql", new MultipleUploadFileParameterAttributes(folderResponse.id(), metadata1));
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter("test.txt", new MultipleUploadFileParameterAttributes(folderResponse.id(), metadata2));

        List<UploadResponse> uploadResponse = getUploadMultipleDocumentExchange(param1, param2, builder)
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();
        Assertions.assertNotNull(uploadResponse);
        UploadResponse uploadResponse1 = uploadResponse.get(0);
        Assertions.assertEquals(param1.filename(), uploadResponse1.name());
        UploadResponse uploadResponse2 = uploadResponse.get(1);
        Assertions.assertEquals(param2.filename(), uploadResponse2.name());

        ListFolderRequest request = new ListFolderRequest(
                folderResponse.id(),
                DocumentType.FILE,
                "text/plain",
                null,
                "tes",
                Map.of("testId", uuid0.toString()),
                testTxtSize,
                SqlUtils.dateToString(OffsetDateTime.now().minusDays(1L)),
                null, //OffsetDateTime.now().plusHours(1L),
                null, //OffsetDateTime.now().minusHours(1L),
                SqlUtils.dateToString(OffsetDateTime.now().plusDays(1L)),
                getUsername()
                , getUsername(),
                new PageCriteria(null, null, 1, 100));
        var graphQlRequest = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      contentType
                      type
                      name
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
                    }
                }
                """.trim();

        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        Mono<ClientGraphQlResponse> response = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> checkListFoldersReturnedSize(doc, 1))
                .expectComplete()
                .verify();


    }

    @Test
    public void whenGetFileInNewFolderGraphQl_thenOK() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder-graphQl"+UUID.randomUUID(), null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
        MultipartBodyBuilder builder = newFileBuilder("test_file_1.sql", "test.txt");

        UUID uuid0 = UUID.randomUUID();

        UUID uuid1 = UUID.randomUUID();
        Map<String, Object> metadata1 = Map.of("testId", uuid0.toString(), "appId", uuid1.toString());

        UUID uuid2 = UUID.randomUUID();
        Map<String, Object> metadata2 = Map.of("testId", uuid0.toString(), "appId", uuid2.toString());

        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter("test_file_1.sql", new MultipleUploadFileParameterAttributes(folderResponse.id(), metadata1));
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter("test.txt", new MultipleUploadFileParameterAttributes(folderResponse.id(), metadata2));

        List<UploadResponse> uploadResponse = getUploadMultipleDocumentExchange(param1, param2, builder)
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();
        Assertions.assertNotNull(uploadResponse);
        UploadResponse uploadResponse1 = uploadResponse.get(0);
        Assertions.assertEquals(param1.filename(), uploadResponse1.name());
        UploadResponse uploadResponse2 = uploadResponse.get(1);
        Assertions.assertEquals(param2.filename(), uploadResponse2.name());
        ListFolderRequest request = new ListFolderRequest(
                folderResponse.id(),
                DocumentType.FILE,
                "text/plain",
                "test.txt",
                null,
                Map.of("testId", uuid0.toString()),
                testTxtSize,
                SqlUtils.dateToString(OffsetDateTime.now().minusHours(1L)),
                SqlUtils.dateToString(OffsetDateTime.now().plusHours(1L)),
                SqlUtils.dateToString(OffsetDateTime.now().minusHours(1L)),
                SqlUtils.dateToString( OffsetDateTime.now().plusHours(1L)),
                getUsername(),
                getUsername(),
                new PageCriteria(null, null, 1, 100));
        var graphQlRequest = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      contentType
                      type
                      name
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
                    }
                }
                """.trim();

        var response = getGraphQlHttpClient()
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> checkListFoldersReturnedSize(doc, 1))
                .expectComplete()
                .verify();

        request = new ListFolderRequest(
                folderResponse.id(),
                DocumentType.FILE,
                "text/plain",
                "test.txt",
                null,
                Map.of("testId", uuid0.toString()),
                testTxtSize,
                null,
                SqlUtils.dateToString(OffsetDateTime.now().plusHours(1L)),
                SqlUtils.dateToString(OffsetDateTime.now().minusHours(1L)),
                null,
                getUsername(),
                getUsername(),
                new PageCriteria(null, null, 1, 100));

        response = getGraphQlHttpClient()
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> checkListFoldersReturnedSize(doc, 1))
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderGraphQl_thenOK() {
        MultipartBodyBuilder builder = newFileBuilder("test_file_1.sql", "test.txt");

        UUID uuid0 = UUID.randomUUID();

        UUID uuid1 = UUID.randomUUID();
        Map<String, Object> metadata1 = Map.of("testId", uuid0.toString(), "appId", uuid1.toString());

        UUID uuid2 = UUID.randomUUID();
        Map<String, Object> metadata2 = Map.of("testId", uuid0.toString(), "appId", uuid2.toString());

        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter("test_file_1.sql", new MultipleUploadFileParameterAttributes(null, metadata1));
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter("test.txt", new MultipleUploadFileParameterAttributes(null, metadata2));

        List<UploadResponse> uploadResponse = getUploadMultipleDocumentExchange(param1, param2, builder)
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();
        Assertions.assertNotNull(uploadResponse);
        UploadResponse uploadResponse1 = uploadResponse.get(0);
        Assertions.assertEquals(param1.filename(), uploadResponse1.name());
        UploadResponse uploadResponse2 = uploadResponse.get(1);
        Assertions.assertEquals(param2.filename(), uploadResponse2.name());

        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        //create post
        ListFolderRequest request = new ListFolderRequest(null, null, null, null, null, Map.of("testId", uuid0.toString()), null, null, null, null, null, null
                , null, new PageCriteria("name", SortOrder.ASC, 1, 100));
        var graphQlRequest = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      contentType
                      type
                      name
                      metadata
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();

        UUID finalUuid = uuid1;
        UUID finalUuid1 = uuid2;
        StepVerifier.create(response)
                .expectNextMatches(doc -> checkListFoldersReturnedSize(doc, 2)
                        && checkListFoldersReturnedItem(doc, 0, "appId", finalUuid.toString())
                        && checkListFoldersReturnedItem(doc, 1, "appId", finalUuid1.toString()))
                .expectComplete()
                .verify();

    }

    private boolean checkListFoldersReturnedItem(ClientGraphQlResponse doc, int itemIndex, String metadataKey, String metadataValue) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Map<String, Object>>) doc.getData()).get("listFolder");
        return ((Map<String, Object>) items.get(itemIndex).get("metadata")).get(metadataKey).equals(metadataValue);
        //return items.stream().anyMatch(map->((Map<String, Object>) map.get("metadata")).get(metadataKey).equals(metadataValue));
    }

    private boolean checkListFoldersReturnedSize(ClientGraphQlResponse doc, int expectedSize) {
        return ((List<Map<String, Object>>) ((Map<String, Map<String, Object>>) doc.getData()).get("listFolder")).size() == expectedSize;
    }


    private HttpGraphQlClient getGraphQlHttpClient() {
        if(graphQlHttpClient == null) {
            graphQlHttpClient = newGraphQlClient();
        }
        return graphQlHttpClient;
    }

    @Test
    void whenUploadDocument_thenCreated() {
        MultipartBodyBuilder builder = newFileBuilder();

        getUploadDocumentExchange(builder).expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("test_file_1.sql");
    }

    @Test
    void whenUploadMultipleDocuments_thenCreated() {
        MultipartBodyBuilder builder = newFileBuilder("test_file_1.sql", "test.txt");

        Map<String, Object> metadata1 = Map.of("helmVersion", "1.0");
        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter("test_file_1.sql", new MultipleUploadFileParameterAttributes(null, metadata1));
        Map<String, Object> metadata2 = Map.of("owner", "OpenFilz");
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter("test.txt", new MultipleUploadFileParameterAttributes(null, metadata2));

        List<UploadResponse> uploadResponse = getUploadMultipleDocumentExchange(param1, param2, builder)
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();
        Assertions.assertNotNull(uploadResponse);
        UploadResponse uploadResponse1 = uploadResponse.get(0);
        Assertions.assertEquals(param1.filename(), uploadResponse1.name());
        UploadResponse uploadResponse2 = uploadResponse.get(1);
        Assertions.assertEquals(param2.filename(), uploadResponse2.name());

        checkFileInfo(uploadResponse1, param1, metadata1, test_file_1_sql_sha);
        checkFileInfo(uploadResponse2, param2, metadata2, test_txt_sha);

    }



    protected void checkFileInfo(UploadResponse uploadResponse, MultipleUploadFileParameter param, Map<String, Object> metadata, String checksum) {
        DocumentInfo info2 = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(uploadResponse.id()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(info2);
        Assertions.assertEquals(param.filename(), info2.name());
        Assertions.assertEquals(metadata, info2.metadata());
    }

    @Test
    void whenUploadTwiceSameDocument_thenConflict() {
        MultipartBodyBuilder builder = newFileBuilder();

        getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated();

        getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

    }

    @Test
    void whenSearchMetadata_thenOK() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));

        UploadResponse uploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(uploadResponse);

        Map<String, Object> metadata = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata")
                        .build(uploadResponse.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(metadata);
        Assertions.assertEquals("OpenFilz", metadata.get("owner"));
        Assertions.assertEquals("MY_APP_1", metadata.get("appId"));

        metadata = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata")
                        .build(uploadResponse.id()))
                .body(BodyInserters.fromValue(new SearchMetadataRequest(List.of("owner"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(metadata);
        Assertions.assertTrue(metadata.containsKey("owner"));
        Assertions.assertEquals("OpenFilz", metadata.get("owner"));
        Assertions.assertFalse(metadata.containsKey("appId"));
    }

    @Test
    void whenSearchIdsByMetadata_thenOK() {
        MultipartBodyBuilder builder = newFileBuilder();
        UUID uuid = UUID.randomUUID();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", uuid.toString()));

        UploadResponse uploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(uploadResponse);

        SearchByMetadataRequest searchByMetadataRequest = new SearchByMetadataRequest(null, null, null, null, Map.of("appId", uuid.toString()));

        List<UUID> uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertEquals(1, uuids.size());
        Assertions.assertEquals(uploadResponse.id(), uuids.getFirst());

        searchByMetadataRequest = new SearchByMetadataRequest(null, DocumentType.FILE, null, null, Map.of("appId", uuid.toString()));

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertEquals(1, uuids.size());
        Assertions.assertEquals(uploadResponse.id(), uuids.getFirst());

        searchByMetadataRequest = new SearchByMetadataRequest(null, DocumentType.FILE, UUID.randomUUID(), null, Map.of("appId", uuid.toString()));

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertTrue(uuids.isEmpty());

        searchByMetadataRequest = new SearchByMetadataRequest("test_file_1.sql", DocumentType.FILE, null, null, Map.of("appId", uuid.toString()));

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertEquals(1, uuids.size());
        Assertions.assertEquals(uploadResponse.id(), uuids.getFirst());

        searchByMetadataRequest = new SearchByMetadataRequest("test_file_1.sql", DocumentType.FILE, null, true, Map.of("appId", uuid.toString()));

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertEquals(1, uuids.size());
        Assertions.assertEquals(uploadResponse.id(), uuids.getFirst());

        searchByMetadataRequest = new SearchByMetadataRequest("test_file_1.sql", DocumentType.FOLDER, null, true, Map.of("appId", uuid.toString()));

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertEquals(0, uuids.size());

        searchByMetadataRequest = new SearchByMetadataRequest("test_file_1.sql", DocumentType.FOLDER, UUID.randomUUID(), true, Map.of("appId", uuid.toString()));

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        searchByMetadataRequest = new SearchByMetadataRequest(null, null, null, null, null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        searchByMetadataRequest = new SearchByMetadataRequest("test_file_1.sql", DocumentType.FILE, null, true, null);

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertFalse(uuids.isEmpty());

        searchByMetadataRequest = new SearchByMetadataRequest("test_file_1.sql", DocumentType.FILE, null, null, null);

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertFalse(uuids.isEmpty());

        searchByMetadataRequest = new SearchByMetadataRequest("test_file_1.sql", null, null, null, null);

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertFalse(uuids.isEmpty());


        //test if 2 files are retrieved
        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test_file_1.sql"));
        builder.part("metadata", Map.of("owner", "Joe", "appId", uuid.toString()));

        UploadResponse uploadResponse2 = uploadDocument(builder);

        Assertions.assertNotNull(uploadResponse2);

        searchByMetadataRequest = new SearchByMetadataRequest(null, null, null, null, Map.of("appId", uuid.toString()));

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertEquals(2, uuids.size());
        Assertions.assertTrue(uuids.contains(uploadResponse.id()));
        Assertions.assertTrue(uuids.contains(uploadResponse2.id()));

        searchByMetadataRequest = new SearchByMetadataRequest(null, null, null, null, Map.of("appId", uuid.toString(), "owner", "Joe"));

        uuids = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uuids);
        Assertions.assertEquals(1, uuids.size());
        Assertions.assertTrue(uuids.contains(uploadResponse2.id()));
    }

    @Test
    void whenSearchMetadata_thenNotFound() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));

        UploadResponse uploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(uploadResponse);

        Map<String, Object> metadata = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata")
                        .build(uploadResponse.id()))
                .body(BodyInserters.fromValue(new SearchMetadataRequest(List.of("owner", "appId"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(metadata);
        Assertions.assertTrue(metadata.containsKey("owner"));
        Assertions.assertEquals("OpenFilz", metadata.get("owner"));
        Assertions.assertTrue(metadata.containsKey("appId"));
        Assertions.assertEquals("MY_APP_1", metadata.get("appId"));

        metadata = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata")
                        .build(uploadResponse.id()))
                .body(BodyInserters.fromValue(new SearchMetadataRequest(List.of("owner1", "appId"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(metadata);
        Assertions.assertFalse(metadata.containsKey("owner1"));
        Assertions.assertFalse(metadata.containsKey("owner"));
        Assertions.assertTrue(metadata.containsKey("appId"));
        Assertions.assertEquals("MY_APP_1", metadata.get("appId"));

        getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata")
                        .build(UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isNotFound();


    }

    @Test
    void whenReplaceContent_thenOK() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse originalUploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(originalUploadResponse);
        UUID id = originalUploadResponse.id();
        Long originalSize = originalUploadResponse.size();
        Assertions.assertTrue(originalSize != null   && originalSize > 0);
        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        getWebTestClient().put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content")
                        .build(id.toString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("test_file_1.sql")
                .jsonPath("$.type").isEqualTo(DocumentType.FILE)
                .jsonPath("$.id").isEqualTo(id.toString());

        DocumentInfo info = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(id.toString()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(info);
        Assertions.assertTrue(info.size() != null && info.size() > 0 && !info.size().equals(originalSize));

    }

    @Test
    void whenLoadFileInCorruptedDatabase_thenError() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse originalUploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(originalUploadResponse);
        UUID id = originalUploadResponse.id();

        corruptStoragePath(id);

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", id)
                        .exchange()
                        .expectStatus().is5xxServerError();
    }

    @Test
    void whenDeleteFileInCorruptedDatabase_thenError() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = uploadDocument(builder);

        Assertions.assertNotNull(response);

        corruptStoragePath(response.id());

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(response.id()));

        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

    }

    @Test
    void whenCopyFileInCorruptedDatabase_thenErrorInCorruptedDatabase_thenError() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = uploadDocument(builder);

        Assertions.assertNotNull(response);

        corruptStoragePath(response.id());

        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder-bb", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        CopyRequest copyRequest = new CopyRequest(Collections.singletonList(response.id()), folderResponse.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/copy")
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().is5xxServerError();

    }

    private void corruptStoragePath(UUID id) {
        databaseClient.sql("update documents set storage_path = :newPath where id = :id")
                .bind("newPath", UUID.randomUUID().toString())
                .bind("id", id)
                .then()
                .block();
    }

    @Test
    void whenReplaceMetadata_thenOK() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));

        UploadResponse originalUploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(originalUploadResponse);
        UUID id = originalUploadResponse.id();
        Long originalSize = originalUploadResponse.size();
        Assertions.assertTrue(originalSize != null   && originalSize > 0);
        Map<String, Object> newMetadata = Map.of("owner", "Google", "clientId", "Joe");
        getWebTestClient().put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata")
                        .build(id.toString()))
                .body(BodyInserters.fromValue(newMetadata))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("test_file_1.sql")
                .jsonPath("$.type").isEqualTo(DocumentType.FILE)
                .jsonPath("$.id").isEqualTo(id.toString());

        DocumentInfo info = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(id.toString()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(info);
        Assertions.assertTrue(info.metadata() != null && info.metadata().equals(newMetadata));

    }

    @Test
    void whenDownloadDocument_thenOk() throws IOException {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = uploadDocument(builder);

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", response.id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/x-sql")
                .expectBody(String.class).isEqualTo(new String(new ClassPathResource("test_file_1.sql").getInputStream().readAllBytes()));
    }

    @Test
    void whenDownloadDocument_thenNotFound() {
        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenDeleteDocument_thenNoContent() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = uploadDocument(builder);

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(response.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", response.id())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenDeleteMetadata_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));

        UploadResponse uploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(uploadResponse);

        DeleteMetadataRequest deleteRequest = new DeleteMetadataRequest(Collections.singletonList("owner"));

        getWebTestClient().method(HttpMethod.DELETE).uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/metadata").build(uploadResponse.id()))
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        DocumentInfo info = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(uploadResponse.id()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(info);
        Assertions.assertEquals("test_file_1.sql", info.name());
        Assertions.assertFalse(info.metadata().containsKey("owner"));
        Assertions.assertTrue(info.metadata().containsKey("appId"));
    }

    @Test
    void whenUpdateMetadata_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));

        UploadResponse uploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(uploadResponse);

        UpdateMetadataRequest updateMetadataRequest = new UpdateMetadataRequest(Map.of("owner", "Joe", "appId",  "MY_APP_2"));

        getWebTestClient().method(HttpMethod.PATCH).uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/metadata").build(uploadResponse.id()))
                .body(BodyInserters.fromValue(updateMetadataRequest))
                .exchange()
                .expectStatus().isOk();

        DocumentInfo info = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(uploadResponse.id()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(info);
        Assertions.assertEquals("test_file_1.sql", info.name());
        Assertions.assertEquals("Joe", info.metadata().get("owner"));
        Assertions.assertEquals("MY_APP_2", info.metadata().get("appId"));
    }

    @Test
    void whenUpdateOrDeleteMetadataWithNoKeys_thenError() {


        UpdateMetadataRequest updateMetadataRequest = new UpdateMetadataRequest(Map.of());

        getWebTestClient().method(HttpMethod.PATCH).uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/metadata").build(UUID.randomUUID().toString()))
                .body(BodyInserters.fromValue(updateMetadataRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        DeleteMetadataRequest deleteRequest = new DeleteMetadataRequest(Collections.emptyList());

        getWebTestClient().method(HttpMethod.DELETE).uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/metadata").build(UUID.randomUUID().toString()))
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().is4xxClientError();


    }

    //FileController Tests

    @Test
    void whenMoveFile_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = uploadDocument(builder);

        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder-a", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MoveRequest moveRequest = new MoveRequest(Collections.singletonList(response.id()), folderResponse.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", folderResponse.id())
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(response.id().toString());
    }

    @Test
    void whenMoveFile_thenError() {

        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder-for-move", null);

        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        createFolderRequest = new CreateFolderRequest("test-folder-for-move", folder.id());

        FolderResponse folder2 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MoveRequest moveRequest = new MoveRequest(Collections.singletonList(folder2.id()), folder.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        moveRequest = new MoveRequest(Collections.singletonList(folder.id()), folder2.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        moveRequest = new MoveRequest(Collections.singletonList(folder2.id()), folder2.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        moveRequest = new MoveRequest(Collections.singletonList(folder.id()), folder2.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse file = uploadDocument(builder);

        moveRequest = new MoveRequest(Collections.singletonList(file.id()), null, false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        moveRequest = new MoveRequest(Collections.singletonList(file.id()), folder.id(), true);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().isOk();

        UploadResponse file2 = uploadDocument(builder);

        moveRequest = new MoveRequest(Collections.singletonList(file2.id()), folder.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        moveRequest = new MoveRequest(Collections.singletonList(file2.id()), folder.id(), true);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().isOk();


    }

    @Test
    void whenCopyFile_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = getUploadResponse(builder);

        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder-b", null);

        UploadResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        CopyRequest copyRequest = new CopyRequest(Collections.singletonList(response.id()), folderResponse.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/copy")
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", folderResponse.id())
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo(response.name());

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", response.id())
                .exchange()
                .expectStatus().isOk();

        copyRequest = new CopyRequest(Collections.singletonList(response.id()), null, false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/copy")
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        copyRequest = new CopyRequest(Collections.singletonList(response.id()), null, true);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/copy")
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenRenameFile_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = uploadDocument(builder);

        RenameRequest renameRequest = new RenameRequest("new-name.sql");

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/files/{fileId}/rename", response.id())
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("new-name.sql");

        renameRequest = new RenameRequest("new-name.sql");

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/files/{fileId}/rename", response.id())
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().is4xxClientError();
    }



    @Test
    void whenDeleteFiles_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = uploadDocument(builder);

        UploadResponse response2 = uploadDocument(newFileBuilder());

        DeleteRequest deleteRequest = new DeleteRequest(List.of(response.id(), response2.id()));

        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", response.id())
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", response2.id())
                .exchange()
                .expectStatus().isNotFound();
    }

    //FolderController tests
    @Test
    void whenCreateFolder_thenOk() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder", null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("test-folder");
    }

    @Test
    void whenCreateFolder_thenError() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test/folder", null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().is4xxClientError();

        createFolderRequest = new CreateFolderRequest("test", UUID.randomUUID());

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenMoveFolder_thenOk() {
        CreateFolderRequest createFolderRequest1 = new CreateFolderRequest("test-folder-1", null);

        FolderResponse folderResponse1 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        CreateFolderRequest createFolderRequest2 = new CreateFolderRequest("test-folder-2", null);

        FolderResponse folderResponse2 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest2))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MoveRequest moveRequest = new MoveRequest(Collections.singletonList(folderResponse1.id()), folderResponse2.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/move")
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list?folderId={id}", folderResponse2.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(folderResponse1.id().toString());
    }

    @Test
    void whenCopyFolder_thenOk() {
        CreateFolderRequest createFolderRequest1 = new CreateFolderRequest("test-folder-to-copy", null);

        FolderResponse folderResponse1 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        CreateFolderRequest createFolderRequest2 = new CreateFolderRequest("target-folder", null);

        FolderResponse folderResponse2 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest2))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        CopyRequest copyRequest = new CopyRequest(Collections.singletonList(folderResponse1.id()), folderResponse2.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list?folderId={id}", folderResponse2.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo(folderResponse1.name());

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list?folderId={id}", folderResponse1.id())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenCopyFolder_thenKO() {
        CreateFolderRequest createFolderRequest1 = new CreateFolderRequest("test-folder-to-copy-9", null);

        FolderResponse folderResponse1 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        CopyRequest copyRequest = new CopyRequest(Collections.singletonList(folderResponse1.id()), folderResponse1.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isForbidden();


    }

    @Test
    void whenCopyFolderRecursive_thenOk() {
        CreateFolderRequest createSourceFolderRequest = new CreateFolderRequest("test-folder-source", null);

        FolderResponse sourceFolderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createSourceFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        CreateFolderRequest createSourceSubFolderRequest = new CreateFolderRequest("test-subfolder-source", sourceFolderResponse.id());

        FolderResponse sourceSubFolderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createSourceSubFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", sourceFolderResponse.id().toString());

        UploadResponse sourceRootFile = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("parentFolderId", sourceSubFolderResponse.id().toString());

        UploadResponse sourceSubFolderFile = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        CreateFolderRequest createFolderRequest2 = new CreateFolderRequest("test-folder-target", null);

        FolderResponse folderResponse2 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest2))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        CopyRequest copyRequest = new CopyRequest(Collections.singletonList(sourceFolderResponse.id()), folderResponse2.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isOk();

        List<FolderElementInfo> targetFolderInfoList = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list?folderId={id}", folderResponse2.id())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(targetFolderInfoList);
        Assertions.assertEquals(1, targetFolderInfoList.size());
        Assertions.assertTrue(targetFolderInfoList.stream().anyMatch(resp->resp.type().equals(DocumentType.FOLDER) && resp.name().equals("test-folder-source")));

        FolderElementInfo targetFolderRoot = targetFolderInfoList.stream().filter(resp -> resp.type().equals(DocumentType.FOLDER) && resp.name().equals("test-folder-source")).findAny().get();

        List<FolderElementInfo> targetFolderRootInfoList = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list?folderId={id}", targetFolderRoot.id())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(targetFolderRootInfoList);
        Assertions.assertEquals(2, targetFolderRootInfoList.size());
        Assertions.assertTrue(targetFolderRootInfoList.stream().anyMatch(resp->resp.type().equals(DocumentType.FILE) && resp.name().equals("test_file_1.sql")));
        Assertions.assertTrue(targetFolderRootInfoList.stream().anyMatch(resp->resp.type().equals(DocumentType.FOLDER) && resp.name().equals("test-subfolder-source")));

        FolderElementInfo subFolderInfo = targetFolderRootInfoList.stream().filter(resp -> resp.type().equals(DocumentType.FOLDER) && resp.name().equals("test-subfolder-source")).findAny().get();

        List<FolderElementInfo> targetSubFolderInfoList = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list?folderId={id}", subFolderInfo.id())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(targetSubFolderInfoList);
        Assertions.assertEquals(1, targetSubFolderInfoList.size());
        Assertions.assertTrue(targetSubFolderInfoList.stream().anyMatch(resp->resp.type().equals(DocumentType.FILE) && resp.name().equals("test.txt")));

    }

    @Test
    void whenDeleteFolderRecursive_thenOk() {
        CreateFolderRequest createSourceFolderRequest = new CreateFolderRequest("test-delete-folder-source", null);

        FolderResponse sourceFolderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createSourceFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        CreateFolderRequest createSourceSubFolderRequest = new CreateFolderRequest("test-delete-subfolder-source", sourceFolderResponse.id());

        FolderResponse sourceSubFolderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createSourceSubFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", sourceFolderResponse.id().toString());

        UploadResponse sourceRootFile = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("parentFolderId", sourceSubFolderResponse.id().toString());

        UploadResponse sourceSubFolderFile = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(sourceFolderResponse.id()));

        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .build(sourceFolderResponse.id()))
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .build(sourceSubFolderResponse.id()))
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .build(sourceRootFile.id()))
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .build(sourceSubFolderFile.id()))
                .exchange()
                .expectStatus().isNotFound();

    }

    @Test
    void whenRenameFolder_thenOk() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("folder-to-rename", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        RenameRequest renameRequest = new RenameRequest("renamed-folder");

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/folders/{folderId}/rename", folderResponse.id())
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("renamed-folder");

        renameRequest = new RenameRequest("renamed-folder");

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/folders/{folderId}/rename", folderResponse.id())
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void whenDeleteFolder_thenOk() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("folder-to-delete", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(folderResponse.id()));

        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", folderResponse.id())
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenListFolder_thenOk() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("folder-to-list", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertEquals("folder-to-list", folderResponse.name());

        List<FolderElementInfo> folders = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertTrue(folders.stream().anyMatch(f -> f.name().equals("folder-to-list")));

    }

    @Test
    void whenListFolder_thenError() {
        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                    .queryParam("onlyFiles", true)
                    .queryParam("onlyFolders", true)
                    .build())
                .exchange()
                .expectStatus().is4xxClientError();

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("onlyFiles", true)
                        .build())
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("onlyFolders", true)
                        .build())
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", UUID.randomUUID().toString())
                        .build())
                .exchange()
                .expectStatus().isNotFound();

    }

    @Test
    void whenSearchAuditTrail_thenOK() {
        MultipartBodyBuilder builder = newFileBuilder();
        String appId = UUID.randomUUID().toString();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", appId));

        UploadResponse uploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(uploadResponse);

        List<AuditLog> auditTrail = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(new SearchByAuditLogRequest(null, null, null, null, Map.of("metadata", Map.of("appId", appId)))))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertEquals(1, auditTrail.size());

        auditTrail = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(new SearchByAuditLogRequest(null, null, null, null, Map.of("metadata", Map.of("owner", "OpenFilz")))))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertFalse(auditTrail.isEmpty());

        auditTrail = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(new SearchByAuditLogRequest(null, null, null, null, Map.of("filename", "test_file_1.sql"))))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertFalse(auditTrail.isEmpty());

        auditTrail = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(new SearchByAuditLogRequest(null, uploadResponse.id(), null, UPLOAD_DOCUMENT, null)))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertFalse(auditTrail.isEmpty());

        RenameRequest renameRequest = new RenameRequest("new-name-for-search_audit.sql");

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/files/{fileId}/rename", uploadResponse.id())
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("new-name-for-search_audit.sql");

        auditTrail = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/audit/search")
                .body(BodyInserters.fromValue(new SearchByAuditLogRequest(getUsername(), uploadResponse.id(), DocumentType.FILE, null, null)))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertEquals(2, auditTrail.size());

    }

    @Test
    void whenGetAuditTrail_thenOK() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("folder-to-delete-for-audit", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(folderResponse.id()));

        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();


        List<AuditLog> auditTrail = getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}")
                        .build(folderResponse.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertEquals(2, auditTrail.size());
        Assertions.assertEquals(DELETE_FOLDER, auditTrail.get(0).action());
        Assertions.assertEquals(CREATE_FOLDER, auditTrail.get(1).action());

        auditTrail = getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}").queryParam("sort", SortOrder.ASC.name())
                        .build(folderResponse.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertEquals(2, auditTrail.size());
        Assertions.assertEquals(DELETE_FOLDER, auditTrail.get(1).action());
        Assertions.assertEquals(CREATE_FOLDER, auditTrail.get(0).action());
    }

    @Test
    void whenDownloadDocumentMultiple_thenError() throws IOException {
        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .body(BodyInserters.fromValue(Collections.emptyList()))
                .exchange()
                .expectStatus().is4xxClientError();

        Resource zip = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .body(BodyInserters.fromValue(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString())))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Resource.class)
                .returnResult().getResponseBody();
        Path targetFolder = Files.createTempDirectory(UUID.randomUUID().toString());
        unzip(zip, targetFolder);
        Assertions.assertEquals(0L, Files.list(targetFolder).count());
    }

    @Test
    void whenDownloadDocumentMultiple_thenOk() throws IOException {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        ClassPathResource file1 = new ClassPathResource("test_file_1.sql");
        builder.part("file", file1);
        ClassPathResource file2 = new ClassPathResource("test.txt");
        builder.part("file", file2);

        List<UploadResponse> uploadResponse = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uploadResponse);
        UploadResponse uploadResponse1 = uploadResponse.get(0);
        UploadResponse uploadResponse2 = uploadResponse.get(1);

        Resource resource = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .body(BodyInserters.fromValue(List.of(uploadResponse1.id(), uploadResponse2.id())))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectBody(Resource.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(resource);
        Assertions.assertEquals("documents.zip", resource.getFilename());

        checkFilesInZip(resource, file1, file2, null);

    }

    void checkFilesInZip(Resource downloadedFile, ClassPathResource file1, ClassPathResource file2, String subfolderName) throws IOException {
        Path targetFolder = Files.createTempDirectory(UUID.randomUUID().toString());
        unzip(downloadedFile, targetFolder);
        int n = 0;
        int k = 0;

        Path temp = Files.createTempDirectory(UUID.randomUUID().toString());
        Path tmpFile1 = temp.resolve(file1.getFilename());
        try (InputStream is = file1.getInputStream()) {
            Files.copy(is, tmpFile1);
        }
        Path tmpFile2 = temp.resolve(file2.getFilename());
        try (InputStream is = file2.getInputStream()) {
            Files.copy(is, tmpFile2);
        }

        if(subfolderName == null) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetFolder)) {
                for (Path file : stream) {
                    if(file.getFileName().toString().equals(file1.getFilename())) {
                        Assertions.assertEquals(-1L, Files.mismatch(file, tmpFile1));
                        n++;
                    } else if(file.getFileName().toString().equals(file2.getFilename())) {
                        Assertions.assertEquals(-1L, Files.mismatch(file, tmpFile2));
                        n++;
                    } else {
                        k++;
                    }
                }
            }
            Assertions.assertEquals(2, n);
            Assertions.assertEquals(0, k);
        } else {
            int f = 0;
            int nf = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetFolder)) {
                for (Path file : stream) {
                    if(file.getFileName().toString().equals(file1.getFilename())) {
                        Assertions.assertEquals(-1L, Files.mismatch(file, tmpFile1));
                        n++;
                    } else if(file.getFileName().toString().equals(file2.getFilename())) {
                        Assertions.assertEquals(-1L, Files.mismatch(file, tmpFile2));
                        n++;
                    } else if(file.getFileName().toString().equals(subfolderName) && Files.isDirectory(file)) {
                        f++;
                        try (DirectoryStream<Path> stream2 = Files.newDirectoryStream(file)) {
                            for (Path sfile : stream2) {
                                if(sfile.getFileName().toString().equals(file1.getFilename())) {
                                    Assertions.assertEquals(-1L, Files.mismatch(sfile, tmpFile1));
                                    nf++;
                                } else if(sfile.getFileName().toString().equals(file2.getFilename())) {
                                    Assertions.assertEquals(-1L, Files.mismatch(sfile, tmpFile2));
                                    nf++;
                                } else {
                                    k++;
                                }
                            }
                        }
                    } else {
                        k++;
                    }

                }
            }
            Assertions.assertEquals(2, n);
            Assertions.assertEquals(2, nf);
            Assertions.assertEquals(1, f);
            Assertions.assertEquals(0, k);
        }


    }

    public static void unzip(final Resource zipFile, final Path targetFolder) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(Channels.newInputStream(Channels.newChannel(zipFile.getInputStream())))) {
            for (ZipEntry entry = zipInputStream.getNextEntry(); entry != null; entry = zipInputStream.getNextEntry()) {
                Path toPath = targetFolder.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectory(toPath);
                } else try (FileChannel fileChannel = FileChannel.open(toPath, WRITE, CREATE/*, DELETE_ON_CLOSE*/)) {
                    fileChannel.transferFrom(Channels.newChannel(zipInputStream), 0, Long.MAX_VALUE);
                }
            }
        }
        log.debug("File {} unzipped in {}", zipFile.getFilename(), targetFolder);
    }
}
