package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for file upload when quotas are disabled (set to 0).
 *
 * This test class configures:
 * - File upload quota: 0 (disabled - no limit per file)
 * - User quota: 0 (disabled - no total storage limit per user)
 *
 * Tests verify that any file size can be uploaded when quotas are disabled.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class QuotaDisabledIT extends TestContainersBaseConfig {

    // Size constants in bytes
    private static final int ONE_KB = 1024;
    private static final int ONE_MB = 1024 * 1024;

    public QuotaDisabledIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureQuotaProperties(DynamicPropertyRegistry registry) {
        // Explicitly set quotas to 0 (disabled)
        registry.add("openfilz.quota.file-upload", () -> 0);
        registry.add("openfilz.quota.user", () -> 0);
    }

    /**
     * Test that large files can be uploaded when file upload quota is disabled (0).
     */
    @Test
    void whenFileQuotaDisabled_thenLargeFileUploadSucceeds() {
        // Create a 2 MB file - would fail if quota was 1 MB
        int largeFileSize = 2 * ONE_MB;
        MultipartBodyBuilder builder = createFileBuilder("large-file-no-quota.txt", largeFileSize);

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals("large-file-no-quota.txt", response.name());
        assertEquals((long) largeFileSize, response.size());
    }

    /**
     * Test that multiple large files can be uploaded when user quota is disabled (0).
     */
    @Test
    void whenUserQuotaDisabled_thenMultipleLargeFilesUploadSucceeds() {
        int fileSize = ONE_MB; // 1 MB each

        // Upload 5 files of 1 MB each = 5 MB total
        // Would fail if user quota was 2 MB
        for (int i = 1; i <= 5; i++) {
            MultipartBodyBuilder builder = createFileBuilder("large-file-" + i + ".txt", fileSize);
            UploadResponse response = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(UploadResponse.class)
                    .returnResult().getResponseBody();

            assertNotNull(response);
            assertNotNull(response.id());
            log.info("Uploaded file {} ({} bytes), total: {} MB", i, fileSize, i);
        }
    }

    /**
     * Test that replacing with a large file succeeds when quotas are disabled.
     */
    @Test
    void whenQuotasDisabled_thenReplaceWithLargeFileSucceeds() {
        // Upload initial small file
        int initialSize = 100 * ONE_KB; // 100 KB
        MultipartBodyBuilder uploadBuilder = createFileBuilder("initial-file.txt", initialSize);
        UploadResponse uploadResponse = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(uploadBuilder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
        assertNotNull(uploadResponse);

        // Replace with a large file (2 MB)
        int replacementSize = 2 * ONE_MB;
        MultipartBodyBuilder replaceBuilder = createFileBuilder("large-replacement.txt", replacementSize);

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/replace-content")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(replaceBuilder.build()))
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * Test that using the standard test file builder works with disabled quotas.
     */
    @Test
    void whenQuotasDisabled_thenStandardUploadSucceeds() {
        // Use the inherited newFileBuilder() method
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertNotNull(response.id());
    }

    /**
     * Creates a MultipartBodyBuilder with a file of the specified size.
     *
     * @param filename    the filename to use
     * @param sizeInBytes the size of the file content in bytes
     * @return configured MultipartBodyBuilder
     */
    private MultipartBodyBuilder createFileBuilder(String filename, int sizeInBytes) {
        byte[] content = new byte[sizeInBytes];
        // Fill with repeating pattern
        for (int i = 0; i < sizeInBytes; i++) {
            content[i] = (byte) ('A' + (i % 26));
        }

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return builder;
    }
}
