package org.openfilz.dms.e2e;

import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/** {@link OnlyOfficeEditorConflictIT} on versioned storage: the storage path never moves there, the version id does. */
public class OnlyOfficeEditorConflictVersionedIT extends OnlyOfficeEditorConflictIT {

    @Container
    static MinIOContainer minio = new MinIOContainer(DockerImageName.parse("ghcr.io/openfilz/minio:RELEASE.2026-10-23T20-06-51Z")
            .asCompatibleSubstituteFor("minio/minio"));

    public OnlyOfficeEditorConflictVersionedIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void versionedStorage(DynamicPropertyRegistry registry) {
        registry.add("storage.minio.endpoint", minio::getS3URL);
        registry.add("storage.minio.access-key", minio::getUserName);
        registry.add("storage.minio.secret-key", minio::getPassword);
        registry.add("storage.type", () -> "minio");
        registry.add("storage.minio.versioning-enabled", () -> true);
    }
}
