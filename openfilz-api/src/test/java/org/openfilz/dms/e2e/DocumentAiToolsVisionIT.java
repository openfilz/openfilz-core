package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * {@code describeImage} on a deployment that does have a chat model: the image is loaded from
 * storage, handed to the model as media, and the answer is labelled by the requested task —
 * describe, ocr or answer. A PDF takes the page-rendering path instead.
 * <p>
 * The model itself is the mock from {@link AiTestConfig}: what is under test is the tool's own
 * work (resolution, type validation, prompt selection, labelling), not the quality of an answer.
 * The MCP suites that pin {@code spring.ai.model.chat=none} cover the opposite case, where the
 * tool tells the calling agent to use its own vision model.
 * <p>
 * Shares its Spring context with the other AI-enabled MCP suites.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
class DocumentAiToolsVisionIT extends AbstractMcpIT {

    private static final String DOCS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;

    DocumentAiToolsVisionIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void visionProperties(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
        registry.add("openfilz.ai.active", () -> true);
    }

    @Test
    @DisplayName("an image is described, OCR'd or questioned, and the answer is labelled accordingly")
    void imagesAreAnalysedPerTask() {
        String suffix = suffix();
        FolderResponse folder = createFolder("vision-" + suffix, null);
        upload("test-image.png", "shot-" + suffix + ".png", folder.id());

        assertThat(callToolText("describeImage", """
                {"imageName":"shot-%s.png"}""".formatted(suffix))).contains("Description of");

        assertThat(callToolText("describeImage", """
                {"imageName":"shot-%s.png","folderName":"vision-%s","task":"ocr"}""".formatted(suffix, suffix)))
                .contains("Text extracted from");

        assertThat(callToolText("describeImage", """
                {"imageName":"shot-%s.png","task":"answer","question":"How many people are in this picture?"}"""
                .formatted(suffix))).contains("About '");

        // 'answer' without a question falls back to a plain description
        assertThat(callToolText("describeImage", """
                {"imageName":"shot-%s.png","task":"answer"}""".formatted(suffix))).contains("About '");

        // A JPEG goes down the same path
        upload("test-image.jpg", "photo-" + suffix + ".jpg", folder.id());
        assertThat(callToolText("describeImage", """
                {"imageName":"photo-%s.jpg"}""".formatted(suffix))).contains("Description of");
    }

    @Test
    @DisplayName("a PDF is rendered page by page before being handed to the model")
    void pdfsGoThroughThePageRenderingPath() {
        String suffix = suffix();
        upload("pdf-example.pdf", "scan-" + suffix + ".pdf", null);

        assertThat(callToolText("describeImage", """
                {"imageName":"scan-%s.pdf","task":"ocr"}""".formatted(suffix)))
                .contains("Text extracted from").contains("scan-" + suffix + ".pdf");
    }

    @Test
    @DisplayName("what is not an image is refused with the list of types that are")
    void unsupportedInputsAreRefused() {
        String suffix = suffix();
        FolderResponse folder = createFolder("vision-bad-" + suffix, null);
        upload("test.txt", "plain-" + suffix + ".txt", folder.id());

        assertThat(callToolText("describeImage", """
                {"imageName":"plain-%s.txt"}""".formatted(suffix)))
                .contains("not a supported image or PDF").contains("application/pdf");

        assertThat(callToolText("describeImage", """
                {"imageName":"vision-bad-%s"}""".formatted(suffix))).contains("is a folder, not an image");

        assertThat(callToolText("describeImage", """
                {"imageName":"ghost-%s.png"}""".formatted(suffix))).contains("not found");

        // An unknown folder hint still lets the global lookup answer
        assertThat(callToolText("describeImage", """
                {"imageName":"ghost-%s.png","folderName":"no-such-folder-%s"}""".formatted(suffix, suffix)))
                .contains("not found");
    }

    // ---------------------------------------------------------------- helpers

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private FolderResponse createFolder(String name, UUID parentId) {
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateFolderRequest(name, parentId))
                .exchange().expectStatus().isCreated()
                .expectBody(FolderResponse.class).returnResult().getResponseBody();
        assertThat(folder).isNotNull();
        return folder;
    }

    /** Upload a classpath fixture under a unique name, so name resolution is unambiguous. */
    private UUID upload(String fixture, String storedName, UUID parentId) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource(fixture) {
            @Override
            public String getFilename() {
                return storedName;
            }
        });
        if (parentId != null) {
            builder.part("parentFolderId", parentId.toString());
        }
        UploadResponse response = getWebTestClient().post()
                .uri(u -> u.path(DOCS + "/upload").queryParam("allowDuplicateFileNames", true).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        return response.id();
    }
}
