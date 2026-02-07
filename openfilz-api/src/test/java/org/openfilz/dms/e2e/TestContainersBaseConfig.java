package org.openfilz.dms.e2e;

import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.MultipleUploadFileParameter;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.net.URI;
import java.util.*;

@Import(GraphQlTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class TestContainersBaseConfig {

    @Value("http://localhost:${local.server.port}${spring.graphql.http.path:/graphql}")
    protected String baseGraphQlHttpPath;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.1").withReuse(true);

    protected WebTestClient webTestClient;
    protected final Jackson2JsonEncoder customJackson2JsonEncoder;

    protected TestContainersBaseConfig(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        this.webTestClient = webTestClient;
        this.customJackson2JsonEncoder = customJackson2JsonEncoder;
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        registry.add("spring.flyway.url", () -> String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getDatabaseName()));
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);

    }

    protected HttpGraphQlClient newGraphQlClient() {

        // 1. Define the ExchangeStrategies to include your custom encoder
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> {

                    // Add the default String/Resource/Form data encoders
                    configurer.defaultCodecs().jackson2JsonEncoder(customJackson2JsonEncoder);

                    // You might need to re-add other required codecs here,
                    // or better: selectively replace just the encoder.

                    // Simple replacement approach: replace the existing Jackson encoder
                    // This is more robust in a typical setup:
                    configurer.defaultCodecs().
                            maxInMemorySize(-1); // Or some other max size

                    configurer.defaultCodecs().
                            jackson2JsonEncoder(customJackson2JsonEncoder);

                })
                .build();

        // 2. Create the WebClient using the strategies
        WebClient webClient = WebClient.builder()
                .baseUrl(baseGraphQlHttpPath)
                .exchangeStrategies(strategies) // <-- Apply the strategies here
                .build();

        // 3. Build the HttpGraphQlClient with the configured WebClient
        return HttpGraphQlClient.builder(webClient).build();
    }


    protected WebTestClient getWebTestClient() {
        return webTestClient;
    }

    protected WebTestClient.ResponseSpec getUploadDocumentExchange(MultipartBodyBuilder builder, String accessToken) {
        return uploadDocument(addAuthorization(getUploadDocumentHeader(builder), accessToken));
    }

    protected WebTestClient.RequestHeadersSpec<?> addAuthorization( WebTestClient.RequestHeadersSpec<?> header, String accessToken) {
        return accessToken != null ? header.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken) : header;
    }

    protected WebTestClient.RequestBodySpec addAuthorizationHeader( WebTestClient.RequestBodySpec request, String accessToken) {
        return accessToken != null ? request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken) : request;
    }

    protected WebTestClient.ResponseSpec getUploadDocumentExchange(MultipartBodyBuilder builder) {
        return uploadDocument(getUploadDocumentHeader(builder));
    }

    protected UploadResponse uploadDocument(MultipartBodyBuilder builder) {
        return getUploadDocumentResponseBody(getUploadDocumentHeader(builder));
    }

    protected UploadResponse getUploadDocumentResponseBody(WebTestClient.RequestHeadersSpec<?> uploadDucumentHeader) {
        return uploadDocument(uploadDucumentHeader)
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
    }

    protected WebTestClient.ResponseSpec uploadDocument(WebTestClient.RequestHeadersSpec<?> uploadDucumentHeader) {
        return uploadDucumentHeader
                .exchange();
    }

    protected WebTestClient.RequestHeadersSpec<?> getUploadDocumentHeader(MultipartBodyBuilder builder) {
        return getWebTestClient().post().uri(uri -> this.getAllowDuplicateFileNames(uri, true))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()));
    }

    private URI getAllowDuplicateFileNames(UriBuilder uri, boolean allowDuplicateFileNames) {
        return uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                .queryParam("allowDuplicateFileNames", allowDuplicateFileNames)
                .build();
    }

    protected UploadResponse getUploadResponse(MultipartBodyBuilder builder, Boolean allowDuplicateFileNames) {
        return getWebTestClient().post().uri(allowDuplicateFileNames != null ? uri -> this.getAllowDuplicateFileNames(uri, allowDuplicateFileNames)  : uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload").build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
    }

    protected UploadResponse getUploadResponse(MultipartBodyBuilder builder) {
        return getUploadResponse(builder, null);
    }

    protected MultipartBodyBuilder newFileBuilder() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test_file_1.sql"));
        return builder;
    }

    protected MultipartBodyBuilder newFileBuilder(String... filenames) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        Arrays.stream(filenames).forEach(filename->builder.part("file", new ClassPathResource(filename)));
        return builder;
    }

    protected WebTestClient.ResponseSpec getUploadMultipleDocumentExchange(MultipleUploadFileParameter param1,
                                                                           MultipleUploadFileParameter param2,
                                                                           MultipartBodyBuilder builder,
                                                                           String accessToken) {
        return getUploadMultipleDocumentExchangeHeader(param1, param2, builder)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    protected WebTestClient.ResponseSpec getUploadMultipleDocumentExchange(MultipleUploadFileParameter param1,
                                                                           MultipleUploadFileParameter param2,
                                                                           MultipartBodyBuilder builder) {
        return getUploadMultipleDocumentExchangeHeader(param1, param2, builder)
                .exchange();
    }

    private WebTestClient.RequestHeadersSpec<?> getUploadMultipleDocumentExchangeHeader(MultipleUploadFileParameter param1, MultipleUploadFileParameter param2, MultipartBodyBuilder builder) {
        builder.part("parametersByFilename", List.of(param1, param2));
        return getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                .queryParam("allowDuplicateFileNames", true)
                .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()));
    }


    protected boolean checkCountIsOK(ClientGraphQlResponse doc, Long expectedCount) {
        return Objects.equals(((Integer) ((Map<String, Object>) doc.getData()).get("count")).longValue(), expectedCount);
    }

    protected boolean checkCountIsGreaterThanZero(ClientGraphQlResponse doc) {
        return (Integer) ((Map<String, Object>) doc.getData()).get("count") > 0;
    }

    protected RestoreHandler createFolderAndFile(String accessToken, String name, UUID parentId) {
        CreateFolderRequest rootFolder1 = new CreateFolderRequest(name, parentId);

        WebTestClient.RequestBodySpec request = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders");

        if(accessToken != null) {
            addAuthorization(request, accessToken);
        }

        FolderResponse rootFolder1Response = request
                .body(BodyInserters.fromValue(rootFolder1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        UploadResponse file1_1 = uploadNewFile(rootFolder1Response.id(), accessToken);

        return new RestoreHandler(rootFolder1Response, file1_1);
    }

    protected RestoreHandler createFolderAndFile(String name, UUID parentId) {
        return createFolderAndFile(null, name, parentId);
    }

    protected UploadResponse newFile(MultipartBodyBuilder builder, String accessToken) {
        return getUploadDocumentExchange(builder,  accessToken)
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
    }

    protected UploadResponse uploadNewFile(UUID parentFolderId, String accessToken) {
        MultipartBodyBuilder builder = newFileBuilder();
        if(parentFolderId != null) {
            builder.part("parentFolderId", parentFolderId.toString());
        }
        return newFile(builder, accessToken);
    }

    protected HttpGraphQlClient newGraphQlClient(String authToken) {
        return newGraphQlClient().mutate().header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken).build();
    }
}
