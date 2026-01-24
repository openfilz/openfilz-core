package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CopyRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.RenameRequest;
import org.openfilz.dms.dto.request.UpdateMetadataRequest;
import org.openfilz.dms.dto.response.CopyResponse;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.e2e.util.PdfLoremGeneratorStreaming;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.query_dsl.MatchQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@AutoConfigureWebTestClient(timeout = "30000")//10 seconds
public class FullTextOpenSearchIT extends FullTextDefaultSearchIT {


    public static final String DEFAULT_INDEX_NAME = "openfilz";
    private static OpenSearchAsyncClient openSearchAsyncClient;

    @Container
    static OpenSearchContainer<?> openSearch = new OpenSearchContainer<>(DockerImageName.parse("opensearchproject/opensearch:3"));

    public FullTextOpenSearchIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.full-text.opensearch.host", () -> openSearch.getHost());
        registry.add("openfilz.full-text.opensearch.port", () -> openSearch.getFirstMappedPort());
        registry.add("openfilz.full-text.opensearch.scheme", () -> openSearch.getHttpHostAddress().substring(0, openSearch.getHttpHostAddress().indexOf(":")));
        registry.add("openfilz.full-text.opensearch.username", () -> openSearch.getUsername());
        registry.add("openfilz.full-text.opensearch.password", () -> openSearch.getPassword());
        registry.add("openfilz.full-text.active", () -> Boolean.TRUE);
    }

    @BeforeAll
    static void init() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        log.debug("Creating OpenSearchAsyncClient for host: {}, port: {}", openSearch.getHost(), openSearch.getFirstMappedPort());
        final HttpHost httpHost = new HttpHost(openSearch.getHttpHostAddress().substring(0, openSearch.getHttpHostAddress().indexOf(":")), openSearch.getHost(), openSearch.getFirstMappedPort());
        final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        //Only for demo purposes. Don't specify your credentials in code.
        credentialsProvider.setCredentials(new AuthScope(httpHost), new UsernamePasswordCredentials(openSearch.getUsername(), openSearch.getPassword().toCharArray()));


        final SSLContext sslcontext = SSLContextBuilder
                .create()
                .loadTrustMaterial(null, (chains, authType) -> true)
                .build();

        final ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(httpHost);
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            final var tlsStrategy = ClientTlsStrategyBuilder.create()
                    .setSslContext(sslcontext)
                    .buildAsync();

            final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder
                    .create()
                    .setTlsStrategy(tlsStrategy)
                    .build();

            return httpClientBuilder
                    .setDefaultCredentialsProvider(credentialsProvider)
                    .setConnectionManager(connectionManager);
        });
        openSearchAsyncClient = new OpenSearchAsyncClient(builder.build());
    }

    protected String getSuggestionQuery1() {
        return "sample file";
    }

    protected String getSuggestionQuery2() {
        return "meeting boss";
    }

    protected String getSearchQuery() {
        return "wonderful day";
    }

    @Test
    void testFullTextInPdf() throws InterruptedException {

        String f0 = "pdf-example.pdf";

        MultipartBodyBuilder builder = newFileBuilder(f0);
        builder.part("metadata", Map.of("owner", "OpenFilz"));
        UploadResponse r0 = getUploadResponse(builder, true);

        waitFor(3000);

        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();

        var graphQlRequest = """
                query {
                  searchDocuments(
                    query: "systèmes d'exploitations",
                    page: 1,
                    size: 20
                  ) {
                    totalHits
                    documents {
                      id                      
                    }
                  }
                }""".trim();

        Mono<ClientGraphQlResponse> response = httpGraphQlClient
                .document(graphQlRequest)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc->{
                    Map<String, Object> searchDocuments = (Map<String, Object>) ((Map<String, Object>) doc.getData()).get("searchDocuments");
                    List<Map<String, String>> documents = (List<Map<String, String>>) searchDocuments.get("documents");
                    return  searchDocuments.get("totalHits").toString().equals("1")
                            && documents.get(0).get("id").equals(r0.id().toString());
                })
                .expectComplete()
                .verify();

        DeleteRequest deleteRequest = new DeleteRequest(List.of(r0.id()));
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        waitFor(3000);
    }

    @Test
    void uploadFileAndVerifyIndex() throws IOException, ExecutionException, InterruptedException {

        MultipartBodyBuilder builder = newFileBuilder();
        String appId = UUID.randomUUID().toString();
        builder.part("metadata", Map.of("owner", "OpenFilz1", "appId", appId));

        UploadResponse response = getUploadResponse(builder);

        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(DEFAULT_INDEX_NAME)
                .query(q -> q.match(MatchQuery.builder()
                        .field("metadata.owner")
                        .query(fv -> fv.stringValue("OpenFilz1")).build()))
                .build();
        waitFor(3000);

        Assertions.assertEquals(1, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());


        UpdateMetadataRequest updateMetadataRequest = new UpdateMetadataRequest(Map.of("owner", "Joe"));

        getWebTestClient().method(HttpMethod.PATCH).uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/metadata")
                        .build(response.id()))
                .body(BodyInserters.fromValue(updateMetadataRequest))
                .exchange()
                .expectStatus().isOk();

        DocumentInfo info = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(response.id()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(info);

        Assertions.assertEquals("Joe", info.metadata().get("owner"));


        waitFor(3000);

        Assertions.assertEquals(0, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());

        searchRequest = new SearchRequest.Builder()
                .index(DEFAULT_INDEX_NAME)
                .query(q -> q.match(MatchQuery.builder()
                        .field("metadata.owner")
                        .query(fv -> fv.stringValue("Joe")).build()))
                .build();

        Assertions.assertEquals(1, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());

        RenameRequest renameRequest = new RenameRequest("new-name.sql");

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/files/{fileId}/rename", response.id())
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("new-name.sql");

        waitFor(3000);

        searchRequest = new SearchRequest.Builder()
                .index(DEFAULT_INDEX_NAME)
                .query(q -> q.match(MatchQuery.builder()
                        .field("name")
                        .query(fv -> fv.stringValue("new-name.sql")).build()))
                .build();
        Assertions.assertEquals(1, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());


        CopyRequest copyRequest = new CopyRequest(Collections.singletonList(response.id()), null, true);

        List<CopyResponse> responseBody = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/copy")
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectBodyList(CopyResponse.class)
                .returnResult().getResponseBody();

        searchRequest = new SearchRequest.Builder()
                .index(DEFAULT_INDEX_NAME)
                .query(q -> q.match(MatchQuery.builder()
                        .field("metadata.appId")
                        .query(fv -> fv.stringValue(appId)).build()))
                .build();

        waitFor(3000);
        Assertions.assertEquals(2, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(response.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        waitFor(3000);
        Assertions.assertEquals(1, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());

    }

    @Test
    void uploadPdfAndSearchText() throws IOException, ExecutionException, InterruptedException {
        MultipartBodyBuilder builder = newFileBuilder("pdf-example.pdf");

        UploadResponse response = getUploadResponse(builder, true);

        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(DEFAULT_INDEX_NAME)
                .query(q -> q.match(MatchQuery.builder()
                        .field("content")
                        .query(fv -> fv.stringValue("systèmes d'exploitations")).build()))
                .build();
        // PDF parsing with Tika happens asynchronously and can take longer
        waitFor(3000);
        Assertions.assertEquals(1, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(response.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        waitFor(3000);
        Assertions.assertEquals(0, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());
    }

    @Disabled
    @Test
    void uploadBigPdf() throws Exception {
        PdfLoremGeneratorStreaming.generate("target/test-classes/test-pdf.pdf", 10 * 1024L);
        MultipartBodyBuilder builder = newFileBuilder("test-pdf.pdf");

        UploadResponse response = getUploadResponse(builder);

        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(DEFAULT_INDEX_NAME)
                .query(q -> q.match(MatchQuery.builder()
                        .field("content")
                        .query(fv -> fv.stringValue("vestibulum")).build()))
                .build();
        waitFor(3000);
        Files.delete(Paths.get("target/test-classes/test-pdf.pdf"));
        Assertions.assertEquals(1, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());
    }



}
