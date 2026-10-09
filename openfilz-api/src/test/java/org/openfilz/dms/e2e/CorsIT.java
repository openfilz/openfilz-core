package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CorsIT extends LocalStorageIT {

    public CorsIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.security.cors-allowed-origins", () -> "http://localhost:4200/");
        registry.add("openfilz.security.cors-allowed-headers", () -> " X-Custom-Reason, X-Other ");
    }

    @Test
    void whenPreflightWithExtraAllowedHeader_thenAllowed() {
        getWebTestClient().options().uri(RestApiVersion.API_PREFIX + "/folders/list")
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization, x-custom-reason")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:4200")
                .expectHeader().value("Access-Control-Allow-Headers", v -> {
                    Assertions.assertTrue(v.toLowerCase().contains("authorization"), v);
                    Assertions.assertTrue(v.toLowerCase().contains("x-custom-reason"), v);
                });
    }

    /** An unlisted header is simply not echoed back — the browser then refuses to send the actual request. */
    @Test
    void whenPreflightWithUnknownHeader_thenNotAllowed() {
        getWebTestClient().options().uri(RestApiVersion.API_PREFIX + "/folders/list")
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization, x-not-allowed")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("Access-Control-Allow-Headers", v -> {
                    Assertions.assertTrue(v.toLowerCase().contains("authorization"), v);
                    Assertions.assertFalse(v.toLowerCase().contains("x-not-allowed"), v);
                });
    }

    @Test
    void whenListFolder2_thenOk() {
        String folderName = "folder-to-list-" + UUID.randomUUID();
        CreateFolderRequest createFolderRequest = new CreateFolderRequest(folderName, null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .header("Origin", "http://localhost:4200")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertEquals(folderName, folderResponse.name());

        List<FolderElementInfo> folders = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list")
                .header("Origin", "http://localhost:4200")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertTrue(folders.stream().anyMatch(f -> f.name().equals(folderName)));

    }

    @Test
    void whenListFolder2_thenKo() {
        String folderName = "folder-to-list-" + UUID.randomUUID();
        CreateFolderRequest createFolderRequest = new CreateFolderRequest(folderName, null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .header("Origin", "http://any-origin.com")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isForbidden();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list")
                .header("Origin", "http://any-origin.com")
                .exchange()
                .expectStatus().isForbidden();


    }
}
