package org.openfilz.dms.e2e;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.MultipleUploadFileParameter;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RequiredArgsConstructor
@Import(GraphQlTestConfig.class)
public abstract class TestContainersBaseConfig {

    @Value("http://localhost:${local.server.port}${spring.graphql.http.path:/graphql}")
    protected String baseGraphQlHttpPath;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18").withReuse(true);

    protected final WebTestClient webTestClient;
    protected final Jackson2JsonEncoder customJackson2JsonEncoder;

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
        return header.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
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
        return getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()));
    }

    protected UploadResponse getUploadResponse(MultipartBodyBuilder builder) {
        return getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
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
}
