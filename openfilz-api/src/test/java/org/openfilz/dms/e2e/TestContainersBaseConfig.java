package org.openfilz.dms.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.MultipleUploadFileParameter;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@RequiredArgsConstructor
public abstract class TestContainersBaseConfig {

    @Value("http://localhost:${local.server.port}${spring.graphql.http.path:/graphql}")
    protected String baseGraphQlHttpPath;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6").withReuse(true);

    @Container
    static final KeycloakContainer keycloak = new KeycloakContainer()
            .withRealmImportFile("keycloak/realm-export.json").withReuse(true);

    protected final WebTestClient webTestClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> keycloak.getAuthServerUrl() + "/realms/your-realm");
    }

    protected static String getAccessToken(String username) {
        return WebClient.builder()
                .baseUrl(keycloak.getAuthServerUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .build()
                .post()
                .uri("/realms/your-realm/protocol/openid-connect/token")
                .body(
                        BodyInserters.fromFormData("grant_type", "password")
                                .with("client_id", "test-client")
                                .with("client_secret", "test-client-secret")
                                .with("username", username)
                                .with("password", "password")
                )
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    try {
                        return new ObjectMapper().readTree(response).get("access_token").asText();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .block();
    }

    protected HttpGraphQlClient newGraphQlClient() {
        return HttpGraphQlClient.builder(WebClient.create(baseGraphQlHttpPath))
                .build();
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
        return webTestClient.post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()));
    }

    protected UploadResponse getUploadResponse(MultipartBodyBuilder builder) {
        return webTestClient.post().uri(RestApiVersion.API_PREFIX + "/documents/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
    }

    protected MultipartBodyBuilder newFileBuilder() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("schema.sql"));
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
        return webTestClient.post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload-multiple")
                .queryParam("allowDuplicateFileNames", true)
                .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()));
    }

}
