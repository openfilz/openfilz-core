package org.openfilz.dms.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.dto.response.quota.QuotaOverview;
import org.openfilz.dms.exception.GlobalExceptionHandler.ErrorResponse;
import org.openfilz.dms.exception.OpenFilzException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The instance-wide quota ({@code openfilz.quota.total}) and the TUS error answers.
 * <p>
 * The instance limit is set at runtime on the {@link QuotaProperties} bean — the way a runtime
 * settings change reaches it — relative to what the instance already holds, so the test does not
 * depend on the other suites' uploads.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class InstanceQuotaIT extends TestContainersBaseConfig {

    private static final int ONE_MB = 1024 * 1024;
    private static final String TUS = RestApiVersion.API_PREFIX + "/tus";

    private final QuotaProperties quotaProperties;
    private final List<UUID> uploaded = new ArrayList<>();

    public InstanceQuotaIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder, QuotaProperties quotaProperties) {
        super(webTestClient, customJacksonJsonEncoder);
        this.quotaProperties = quotaProperties;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.tus.max-upload-size", () -> 5L * ONE_MB);
    }

    @AfterEach
    void reset() {
        quotaProperties.setTotal(0);
        if (!uploaded.isEmpty()) {
            getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                    .body(BodyInserters.fromValue(new DeleteRequest(List.copyOf(uploaded)))).exchange();
            uploaded.clear();
        }
    }

    /** Sets the instance limit to what the instance holds now plus {@code headroomMb}. */
    private void instanceLimitWithHeadroom(int headroomMb) {
        QuotaOverview overview = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/admin/quotas").exchange()
                .expectStatus().isOk().expectBody(QuotaOverview.class).returnResult().getResponseBody();
        assertNotNull(overview);
        quotaProperties.setTotal((int) (overview.instanceUsedBytes() / ONE_MB) + 1 + headroomMb);
    }

    @Test
    void upload_overTheInstanceLimit_is507WithItsOwnCode() {
        instanceLimitWithHeadroom(1);

        ErrorResponse refused = upload("instance-over.bin", 3 * ONE_MB)
                .expectStatus().isEqualTo(HttpStatus.INSUFFICIENT_STORAGE)
                .expectBody(ErrorResponse.class).returnResult().getResponseBody();
        assertNotNull(refused);
        assertEquals(OpenFilzException.INSTANCE_QUOTA_EXCEEDED, refused.error());
        assertTrue(refused.message().contains("storage of this instance is full"));

        UploadResponse ok = upload("instance-within.bin", ONE_MB / 4)
                .expectStatus().isCreated().expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertNotNull(ok);
        uploaded.add(ok.id());

        QuotaOverview overview = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/admin/quotas").exchange()
                .expectStatus().isOk().expectBody(QuotaOverview.class).returnResult().getResponseBody();
        assertNotNull(overview);
        assertEquals(quotaProperties.getTotal(), overview.totalMb());
        assertEquals((long) quotaProperties.getTotal() * ONE_MB, overview.instanceLimitBytes());
    }

    @Test
    void tusCreate_overTheInstanceLimit_is507WithAJsonBody() {
        instanceLimitWithHeadroom(1);

        ErrorResponse refused = getWebTestClient().post().uri(TUS)
                .header("Upload-Length", String.valueOf(3 * ONE_MB))
                .header("Upload-Metadata", metadata("tus-instance-over.bin"))
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INSUFFICIENT_STORAGE)
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .expectBody(ErrorResponse.class).returnResult().getResponseBody();
        assertNotNull(refused);
        assertEquals(OpenFilzException.INSTANCE_QUOTA_EXCEEDED, refused.error());
    }

    @Test
    void tusCreate_overTheTusMaximum_is413WithAJsonBody() {
        ErrorResponse refused = getWebTestClient().post().uri(TUS)
                .header("Upload-Length", String.valueOf(6L * ONE_MB))
                .header("Upload-Metadata", metadata("tus-too-big.bin"))
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
                .expectBody(ErrorResponse.class).returnResult().getResponseBody();
        assertNotNull(refused);
        assertEquals(OpenFilzException.FILE_SIZE_EXCEEDED, refused.error());
        assertNotNull(refused.message());
    }

    @Test
    void tusPatch_pastTheDeclaredLength_is413_andNothingIsCounted() {
        String location = getWebTestClient().post().uri(TUS)
                .header("Upload-Length", "10")
                .header("Upload-Metadata", metadata("tus-bounded-" + UUID.randomUUID() + ".txt"))
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Void.class).getResponseHeaders().getLocation().toString();
        String uploadId = location.substring(location.lastIndexOf('/') + 1);

        byte[] tooMuch = new byte[20];
        ErrorResponse refused = getWebTestClient().patch().uri(TUS + "/{id}", uploadId)
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(tooMuch.length))
                .header("Tus-Resumable", "1.0.0")
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(tooMuch)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
                .expectBody(ErrorResponse.class).returnResult().getResponseBody();
        assertNotNull(refused);
        assertTrue(refused.message().contains("Upload-Length"));

        // The offset did not move
        getWebTestClient().head().uri(TUS + "/{id}", uploadId).header("Tus-Resumable", "1.0.0").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Upload-Offset", "0");

        getWebTestClient().delete().uri(TUS + "/{id}", uploadId).exchange();
    }

    private static String metadata(String filename) {
        return "filename " + Base64.getEncoder().encodeToString(filename.getBytes())
                + ",allowDuplicateFileNames " + Base64.getEncoder().encodeToString("true".getBytes());
    }

    private WebTestClient.ResponseSpec upload(String filename, int size) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(new byte[size]) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload").queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange();
    }
}
