package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.*;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.openfilz.dms.enums.AuditAction.CREATE_FOLDER;
import static org.openfilz.dms.enums.AuditAction.DELETE_FOLDER;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class StatisticsIT extends TestContainersBaseConfig {


    public StatisticsIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }


    @Test
    void whenGetStatistics_thenOk() {
        RestoreHandler folderAndFile1 = createFolderAndFile("test-stats-folder-1", null);
        RestoreHandler folderAndFile2 = createFolderAndFile("test-stats-folder-2", null);
        RestoreHandler folderAndFile1_1 = createFolderAndFile("test-stats-folder-1-1", folderAndFile1.parent().id());
        RestoreHandler folderAndFile2_1 = createFolderAndFile("test-stats-folder-2-1", folderAndFile2.parent().id());
        RestoreHandler folderAndFile1_2 = createFolderAndFile("test-stats-folder-1-2", folderAndFile1.parent().id());
        RestoreHandler folderAndFile2_2 = createFolderAndFile("test-stats-folder-2-2", folderAndFile2.parent().id());
        RestoreHandler folderAndFile1_1_1 = createFolderAndFile("test-stats-folder-1-1-1", folderAndFile1_1.parent().id());
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse rootFile = uploadDocument(builder);

        builder = newFileBuilder();
        builder.part("parentFolderId", folderAndFile1_1_1.parent().id().toString());
        UploadResponse file1_1_1_2 = uploadDocument(builder);

        DashboardStatisticsResponse stats = getWebTestClient().method(HttpMethod.GET).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DASHBOARD + "/statistics")
                .exchange()
                .expectStatus().isOk()
                .expectBody(DashboardStatisticsResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(stats);
        Assertions.assertEquals(9, stats.totalFiles());
        Assertions.assertEquals(7, stats.totalFolders());
        Assertions.assertEquals(9, stats.fileTypeCounts().stream().filter(type -> type.type().equals("documents")).findFirst().get().count());


    }





    record RestoreHandler(FolderResponse parent, UploadResponse file) {}


    private RestoreHandler createFolderAndFile(String name, UUID parentId) {
        CreateFolderRequest rootFolder1 = new CreateFolderRequest(name, parentId);


        FolderResponse rootFolder1Response = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(rootFolder1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", rootFolder1Response.id().toString());

        UploadResponse file1_1 = uploadDocument(builder);

        return new RestoreHandler(rootFolder1Response, file1_1);
    }



}
