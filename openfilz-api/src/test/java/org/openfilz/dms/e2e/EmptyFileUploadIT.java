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
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Zero-byte uploads into a folder.
 * <p>
 * Up to Spring Framework 7.0.9, WebFlux's {@code DefaultPartHttpMessageReader} silently dropped a zero-byte
 * part that was FOLLOWED by another part, when the empty part and the next boundary arrived in the same
 * buffer (what a browser does for a small body). Framework 7.0.10 (Spring Boot 4.1.2) no longer drops it.
 * Clients (openfilz-web, openfilz-web-ee, openfilz-desktop) still send the file part(s) last. These tests
 * cover both part orders, so a parser regression shows up here.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class EmptyFileUploadIT extends TestContainersBaseConfig {

    public EmptyFileUploadIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void whenUploadEmptyFileLastWithParentFolder_thenCreatedInFolder() {
        FolderResponse folder = createFolder();

        // Part order sent by the web apps: fields first, file last
        UploadResponse response = postRaw("/documents/upload",
                field("parentFolderId", folder.id().toString()),
                field("metadata", "{\"origin\":\"empty-file-it\"}"),
                emptyFile("empty.txt"))
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.id());
        Assertions.assertEquals("empty.txt", response.name());
        Assertions.assertEquals(0L, response.size());
        assertFolderContainsOnly(folder, "empty.txt");
    }

    @Test
    void whenUploadMultipleEmptyFileLastWithParameters_thenCreatedInFolder() {
        FolderResponse folder = createFolder();

        List<UploadResponse> responses = postRaw("/documents/upload-multiple",
                jsonField("parametersByFilename",
                        "[{\"filename\":\"empty.txt\",\"fileAttributes\":{\"parentFolderId\":\"" + folder.id() + "\"}}]"),
                emptyFile("empty.txt"))
                .expectStatus().is2xxSuccessful()
                .expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(responses);
        Assertions.assertEquals(1, responses.size());
        Assertions.assertFalse(responses.getFirst().isError(), () -> "Upload failed: " + responses.getFirst());
        Assertions.assertEquals(0L, responses.getFirst().size());
        assertFolderContainsOnly(folder, "empty.txt");
    }

    /**
     * Empty file part FOLLOWED by another part, in one buffer: dropped by the parser up to Spring
     * Framework 7.0.9 (the server answered 400), kept since 7.0.10.
     */
    @Test
    void whenUploadEmptyFileFirstWithParentFolder_thenCreatedInFolder() {
        FolderResponse folder = createFolder();

        UploadResponse response = postRaw("/documents/upload",
                emptyFile("empty.txt"),
                field("parentFolderId", folder.id().toString()))
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertEquals("empty.txt", response.name());
        Assertions.assertEquals(0L, response.size());
        assertFolderContainsOnly(folder, "empty.txt");
    }

    /*
     * Raw multipart bodies, sent as ONE buffer like a browser or HttpClient does for a small request.
     * MultipartBodyBuilder cannot be used here: its writer flushes each part as a separate buffer, and
     * the parser only drops the empty part when it and the next boundary arrive in the same buffer.
     */
    private static final String BOUNDARY = "empty-file-it-boundary";
    private static final String CRLF = "\r\n";

    private WebTestClient.ResponseSpec postRaw(String path, String... parts) {
        String body = String.join("", parts) + "--" + BOUNDARY + "--" + CRLF;
        return getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + path)
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
                .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                .exchange();
    }

    private static String field(String name, String value) {
        return "--" + BOUNDARY + CRLF
                + "Content-Disposition: form-data; name=\"" + name + "\"" + CRLF
                + CRLF + value + CRLF;
    }

    private static String jsonField(String name, String json) {
        return "--" + BOUNDARY + CRLF
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"blob\"" + CRLF
                + "Content-Type: application/json" + CRLF
                + CRLF + json + CRLF;
    }

    private static String emptyFile(String filename) {
        return "--" + BOUNDARY + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + CRLF
                + "Content-Type: text/plain" + CRLF
                + CRLF + CRLF;
    }

    private FolderResponse createFolder() {
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest("empty-upload-" + UUID.randomUUID(), null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(folder);
        return folder;
    }

    private void assertFolderContainsOnly(FolderResponse folder, String name) {
        List<FolderElementInfo> elements = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                                .queryParam("folderId", folder.id())
                                .queryParam("onlyFiles", true)
                                .build())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(elements);
        Assertions.assertEquals(List.of(name), elements.stream().map(FolderElementInfo::name).toList());
    }
}
