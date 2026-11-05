package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.service.ai.McpFileInspector;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * An inspector that refuses a file stops both binary write tools before anything is written, and
 * reads the decoded bytes rather than the base64.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import({AiTestConfig.class, McpFileInspectorIT.InspectorConfig.class})
class McpFileInspectorIT extends AbstractMcpIT {

    private static final String REFUSED_MARKER = "refuse-this-file";
    private static final String BROKEN_MARKER = "break-the-inspector";
    private static final String SLOW_MARKER = "inspect-this-slowly";
    private static final String INSPECTOR_SECRET = "scanner.internal:3310";
    /** What happens while a file carrying {@link #SLOW_MARKER} is being inspected. */
    private static final AtomicReference<Runnable> DURING_INSPECTION = new AtomicReference<>();

    McpFileInspectorIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
        registry.add("openfilz.ai.active", () -> true);
    }

    @Test
    @DisplayName("uploadFile: a refused file is stored nowhere, an accepted one is stored")
    void upload() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String refused = "refused-" + suffix + ".bin";

        assertThat(callToolText("uploadFile", """
                {"fileName":"%s","contentBase64":"%s"}""".formatted(refused, base64(REFUSED_MARKER))))
                .contains("'" + refused + "' was refused: it looks wrong").contains("Nothing was written")
                .doesNotContain("Uploaded");
        assertThat(callToolText("queryDocuments", """
                {"folder":"all","nameLike":"%s","type":"FILE","pageSize":10}""".formatted(refused)))
                .doesNotContain(refused);

        String accepted = "accepted-" + suffix + ".bin";
        assertThat(callToolText("uploadFile", """
                {"fileName":"%s","contentBase64":"%s"}""".formatted(accepted, base64("fine"))))
                .contains("Uploaded '" + accepted + "'");
    }

    @Test
    @DisplayName("replaceDocumentContent: a refused file leaves the content as it was")
    void replace() {
        String name = "kept-" + UUID.randomUUID().toString().substring(0, 8) + ".bin";
        UUID id = idIn(callToolText("uploadFile", """
                {"fileName":"%s","contentBase64":"%s"}""".formatted(name, base64("the original"))));

        assertThat(callToolText("replaceDocumentContent", """
                {"document":"%s","contentBase64":"%s"}""".formatted(id, base64(REFUSED_MARKER))))
                .contains("was refused: it looks wrong").contains("Nothing was written")
                .doesNotContain("Replaced");

        assertThat(download(id)).isEqualTo("the original".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an inspector that errs writes nothing, and its message stays out of the answer")
    void anInspectorThatErrsRefuses() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String unchecked = "unchecked-" + suffix + ".bin";

        assertThat(callToolText("uploadFile", """
                {"fileName":"%s","contentBase64":"%s"}""".formatted(unchecked, base64(BROKEN_MARKER))))
                .contains("'" + unchecked + "' could not be checked").contains("Nothing was written")
                .doesNotContain(INSPECTOR_SECRET).doesNotContain("Uploaded");
        assertThat(callToolText("queryDocuments", """
                {"folder":"all","nameLike":"%s","type":"FILE","pageSize":10}""".formatted(unchecked)))
                .doesNotContain(unchecked);

        String name = "kept-" + suffix + ".bin";
        UUID id = idIn(callToolText("uploadFile", """
                {"fileName":"%s","contentBase64":"%s"}""".formatted(name, base64("the original"))));
        assertThat(callToolText("replaceDocumentContent", """
                {"document":"%s","contentBase64":"%s"}""".formatted(id, base64(BROKEN_MARKER))))
                .contains("could not be checked").contains("Nothing was written")
                .doesNotContain(INSPECTOR_SECRET).doesNotContain("Replaced");
        assertThat(download(id)).isEqualTo("the original".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("replaceDocumentContent: a write landing during the inspection is not overwritten")
    void theExpectedVersionIsComparedAfterTheInspection() {
        String name = "raced-" + UUID.randomUUID().toString().substring(0, 8) + ".bin";
        String uploaded = callToolText("uploadFile", """
                {"fileName":"%s","contentBase64":"%s"}""".formatted(name, base64("the original")));
        UUID id = idIn(uploaded);
        String v1 = versionIn(uploaded);
        // Someone else replaces the content while this call's file is being inspected
        DURING_INSPECTION.set(() -> assertThat(callToolText("replaceDocumentContent", """
                {"document":"%s","contentBase64":"%s"}""".formatted(id, base64("written meanwhile")))).contains("Replaced"));

        assertThat(callToolText("replaceDocumentContent", """
                {"document":"%s","contentBase64":"%s","expectedVersionId":"%s"}""".formatted(id, base64(SLOW_MARKER), v1)))
                .contains("has changed since").contains("Nothing was written");

        assertThat(DURING_INSPECTION.get()).as("the inspection ran").isNull();
        assertThat(download(id)).isEqualTo("written meanwhile".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("uploadFile: a name with a control character is refused before anything is logged or inspected")
    void aControlCharacterInAName() {
        assertThat(callToolText("uploadFile", """
                {"fileName":"a\\nb.bin","contentBase64":"%s"}""".formatted(base64("fine"))))
                .contains("no control character").doesNotContain("Uploaded");
    }

    private static String versionIn(String answer) {
        Matcher version = Pattern.compile("version (\\S+?)\\.?\"?$").matcher(answer.strip());
        assertThat(version.find()).as("a version in: %s", answer).isTrue();
        return version.group(1);
    }

    private static String base64(String content) {
        return Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID idIn(String answer) {
        Matcher id = Pattern.compile("id ([0-9a-f-]{36})").matcher(answer);
        assertThat(id.find()).as("an id in: %s", answer).isTrue();
        return UUID.fromString(id.group(1));
    }

    private byte[] download(UUID id) {
        return getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS + "/{id}/download", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
    }

    @TestConfiguration
    static class InspectorConfig {
        @Bean
        @Primary
        McpFileInspector refusingInspector() {
            return (file, fileName) -> {
                try {
                    String content = Files.readString(file);
                    if (content.contains(BROKEN_MARKER)) {
                        return Mono.error(new IllegalStateException("Connection refused: " + INSPECTOR_SECRET));
                    }
                    if (content.contains(SLOW_MARKER)) {
                        Runnable meanwhile = DURING_INSPECTION.getAndSet(null);
                        if (meanwhile != null) {
                            meanwhile.run();
                        }
                    }
                    return content.contains(REFUSED_MARKER) ? Mono.just("it looks wrong") : Mono.empty();
                } catch (IOException e) {
                    return Mono.error(e);
                }
            };
        }
    }
}
