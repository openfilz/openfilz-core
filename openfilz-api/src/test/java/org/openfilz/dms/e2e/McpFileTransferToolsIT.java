package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The binary write tools through the real endpoint: bytes in, the same bytes out, and the refusals
 * a caller can meet. The size limits matter here because a tool argument travels inside one JSON
 * body, which the default codec limit (256 KB) would stop long before the file limit.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
class McpFileTransferToolsIT extends AbstractMcpIT {

    private static final String DOCS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;
    /** The answer of a tool arrives as a JSON string, hence the closing quote. */
    private static final Pattern VERSION = Pattern.compile("version (\\S+?)\\.?\"?$");
    private static final int TEN_MB = 10 * 1024 * 1024;

    McpFileTransferToolsIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
        registry.add("openfilz.ai.active", () -> true);
    }

    @Test
    @DisplayName("a file uploaded then replaced comes back byte for byte")
    void uploadAndReplaceRoundTrip() {
        String name = "book-" + suffix() + ".xlsx";
        byte[] first = randomBytes(40_000);

        String uploaded = callToolText("uploadFile", arguments(name, first, null));
        assertThat(uploaded).contains("Uploaded '" + name + "'").contains("40000 bytes");
        assertThat(download(idIn(uploaded))).isEqualTo(first);

        byte[] second = randomBytes(60_000);
        String replaced = callToolText("replaceDocumentContent", """
                {"document":"%s","contentBase64":"%s"}""".formatted(name, base64(second)));
        assertThat(replaced).contains("Replaced '" + name + "'").contains("60000 bytes");
        assertThat(download(idIn(uploaded))).isEqualTo(second);
    }

    @Test
    @DisplayName("the expected version is checked: a stale one writes nothing and names the current one")
    void staleExpectedVersionIsRefused() {
        String name = "locked-" + suffix() + ".bin";
        String uploaded = callToolText("uploadFile", arguments(name, randomBytes(100), null));
        UUID id = idIn(uploaded);
        String v1 = versionIn(uploaded);

        byte[] second = randomBytes(200);
        String replaced = callToolText("replaceDocumentContent", """
                {"document":"%s","contentBase64":"%s","expectedVersionId":"%s"}""".formatted(id, base64(second), v1));
        assertThat(replaced).contains("Replaced");
        String v2 = versionIn(replaced);
        assertThat(v2).isNotEqualTo(v1);

        // Based on the first version, while the second is current
        String stale = callToolText("replaceDocumentContent", """
                {"document":"%s","contentBase64":"%s","expectedVersionId":"%s"}""".formatted(id, base64(randomBytes(300)), v1));
        assertThat(stale).contains("has changed since").contains(v2).contains("Nothing was written");
        assertThat(download(id)).isEqualTo(second);
    }

    @Test
    @DisplayName("a partial name, a folder, bad base64 and a path in a name are refused")
    void refusals() {
        String suffix = suffix();
        String name = "target-" + suffix + ".bin";
        callToolText("uploadFile", arguments(name, randomBytes(10), null));
        FolderResponse folder = createFolder("transfer-" + suffix);

        assertThat(callToolText("replaceDocumentContent", """
                {"document":"target-%s","contentBase64":"AAAA"}""".formatted(suffix)))
                .contains("You cannot replace").contains("Partial names are not accepted");
        assertThat(callToolText("replaceDocumentContent", """
                {"document":"%s","contentBase64":"AAAA"}""".formatted(folder.id()))).contains("is a folder");
        assertThat(callToolText("replaceDocumentContent", """
                {"document":"%s","contentBase64":"not base64!"}""".formatted(name))).contains("not valid base64");
        assertThat(callToolText("uploadFile", """
                {"fileName":"../evil.bin","contentBase64":"AAAA"}""")).contains("no path in it");
        assertThat(callToolText("uploadFile", """
                {"fileName":"x-%s.bin","contentBase64":"AAAA","folderName":"no-such-%s"}""".formatted(suffix, suffix)))
                .contains("No folder named");

        // Into a folder, by name
        String inFolder = callToolText("uploadFile", arguments("inside-" + suffix + ".bin", randomBytes(10), "transfer-" + suffix));
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(idIn(inFolder)))).contains("transfer-" + suffix);
    }

    @Test
    @DisplayName("10 MB goes through the real endpoint, byte for byte")
    void tenMegabytesThroughTheEndpoint() {
        byte[] bytes = randomBytes(TEN_MB);
        String uploaded = callToolText("uploadFile", arguments("big-" + suffix() + ".bin", bytes, null));

        assertThat(uploaded).contains("Uploaded").contains(TEN_MB + " bytes");
        assertThat(Arrays.equals(download(idIn(uploaded)), bytes)).isTrue();
    }

    @Test
    @DisplayName("just above the limit, the tool refuses and says what to do")
    void aboveTheFileLimitIsRefusedByTheTool() {
        // Fits the body limit (which leaves room for the JSON around it), not the file limit
        String answer = callToolText("uploadFile", arguments("over-" + suffix() + ".bin", randomBytes(TEN_MB + 3000), null));

        assertThat(answer).contains("above this server's").contains("Upload it in the OpenFilz application");
    }

    @Test
    @DisplayName("a body above the limit is refused with 413, declared or chunked, before it is buffered")
    void oversizedBodyIsRefusedByTheEndpoint() {
        String header = """
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"uploadFile","arguments":{"fileName":"x.bin","contentBase64":\"""";
        byte[] oversized = (header + "A".repeat(20 * 1024 * 1024) + "\"}}}").getBytes(StandardCharsets.UTF_8);

        postRaw().bodyValue(oversized).exchange().expectStatus().isEqualTo(413);

        // No Content-Length to go by: the limit applies while reading
        Flux<DataBuffer> chunks = Flux.range(0, 21).map(i -> {
            byte[] chunk = i == 0 ? header.getBytes(StandardCharsets.UTF_8) : "A".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8);
            return DefaultDataBufferFactory.sharedInstance.wrap(chunk);
        });
        postRaw().body(chunks, DataBuffer.class).exchange().expectStatus().isEqualTo(413);
    }

    @Test
    @DisplayName("a normal request is unaffected: the limit is on /mcp only, the rest of the API keeps its own")
    void theRestOfTheApiKeepsItsLimit() {
        // A JSON body above the default 256 KB to an ordinary endpoint is still refused
        String oversized = "{\"name\":\"" + "x".repeat(400 * 1024) + "\"}";
        webClientLong().post().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FOLDERS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(oversized)
                .exchange().expectStatus().isEqualTo(413);
    }

    // ---------------------------------------------------------------- helpers

    private WebTestClient webClientLong() {
        return getWebTestClient().mutate().responseTimeout(Duration.ofMinutes(2))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024)).build();
    }

    private WebTestClient.RequestBodySpec postRaw() {
        return webClientLong().post().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static String arguments(String name, byte[] bytes, String folder) {
        return folder == null
                ? """
                {"fileName":"%s","contentBase64":"%s"}""".formatted(name, base64(bytes))
                : """
                {"fileName":"%s","contentBase64":"%s","folderName":"%s"}""".formatted(name, base64(bytes), folder);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static UUID idIn(String answer) {
        Matcher id = Pattern.compile("id ([0-9a-f-]{36})").matcher(answer);
        assertThat(id.find()).as("an id in: %s", answer).isTrue();
        return UUID.fromString(id.group(1));
    }

    private static String versionIn(String answer) {
        Matcher version = VERSION.matcher(answer);
        assertThat(version.find()).as("a version in: %s", answer).isTrue();
        return version.group(1);
    }

    private byte[] download(UUID id) {
        return webClientLong().get().uri(DOCS + "/{id}/download", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
    }

    private FolderResponse createFolder(String name) {
        return getWebTestClient().post().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FOLDERS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateFolderRequest(name, null))
                .exchange().expectStatus().isCreated()
                .expectBody(FolderResponse.class).returnResult().getResponseBody();
    }
}
