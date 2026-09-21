package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * A document's size is the length of its file. Browsers, curl and the SDKs send a multipart
 * request <em>with</em> a Content-Length — the file plus its envelope — and that figure must not
 * end up as the size. (WebTestClient streams multipart bodies without a Content-Length, which is
 * why the other suites never saw the difference: here the body is built by hand.)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class DocumentSizeIT extends TestContainersBaseConfig {

    private static final String BOUNDARY = "----openfilz-size-it";

    public DocumentSizeIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void anUploadedDocumentHasTheSizeOfItsFileNotOfTheRequest() {
        byte[] file = "thirty-four bytes of content here!".getBytes(StandardCharsets.UTF_8);
        byte[] request = multipart("size-" + UUID.randomUUID() + ".txt", file);

        UploadResponse uploaded = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/upload")
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
                .contentLength(request.length)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(uploaded);
        assertEquals(file.length, uploaded.size());
        assertEquals(file.length, sizeOf(uploaded.id()));
    }

    @Test
    void aReplacedDocumentHasTheSizeOfItsNewFile() {
        UploadResponse uploaded = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/upload")
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
                .bodyValue(multipart("replace-" + UUID.randomUUID() + ".txt", "first".getBytes(StandardCharsets.UTF_8)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
        assertNotNull(uploaded);

        byte[] replacement = "the second version is longer".getBytes(StandardCharsets.UTF_8);
        byte[] request = multipart("replacement.txt", replacement);
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", uploaded.id())
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
                .contentLength(request.length)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();

        assertEquals(replacement.length, sizeOf(uploaded.id()));
    }

    private long sizeOf(UUID documentId) {
        DocumentInfo info = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info").queryParam("withMetadata", true).build(documentId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        assertNotNull(info);
        return info.size();
    }

    private static byte[] multipart(String filename, byte[] content) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }
}
