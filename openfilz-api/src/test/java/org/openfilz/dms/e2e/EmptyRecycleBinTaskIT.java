package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class EmptyRecycleBinTaskIT extends TestContainersBaseConfig {

    protected HttpGraphQlClient graphQlHttpClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.soft-delete.active", () -> true);
        registry.add("openfilz.soft-delete.recycle-bin.enabled", () -> true);
        registry.add("openfilz.soft-delete.recycle-bin.cleanup-cron", () -> "*/1 * * * * *");
        registry.add("openfilz.soft-delete.recycle-bin.auto-cleanup-interval", () -> "1 second");
    }

    public EmptyRecycleBinTaskIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    private HttpGraphQlClient getGraphQlHttpClient() {
        if(graphQlHttpClient == null) {
            graphQlHttpClient = newGraphQlClient();
        }
        return graphQlHttpClient;
    }

    protected void waitFor(long timeout) throws InterruptedException {
        CountDownLatch latch2 = new CountDownLatch(1);
        new Thread(() -> {
            log.info("Waiting "+timeout+" milliseconds...");
            try { Thread.sleep(timeout); } catch (InterruptedException ignored) {}
            latch2.countDown();
        }).start();
        latch2.await();
    }

    @Test
    void testEmptyRecycleBinTask() throws InterruptedException {
        RestoreHandler folderAndFile1 = createFolderAndFile("test-restore-folder-1", null);
        RestoreHandler folderAndFile2 = createFolderAndFile("test-restore-folder-2", null);
        RestoreHandler folderAndFile1_1 = createFolderAndFile("test-restore-folder-1-1", folderAndFile1.parent().id());
        RestoreHandler folderAndFile2_1 = createFolderAndFile("test-restore-folder-2-1", folderAndFile2.parent().id());
        RestoreHandler folderAndFile1_2 = createFolderAndFile("test-restore-folder-1-2", folderAndFile1.parent().id());
        RestoreHandler folderAndFile2_2 = createFolderAndFile("test-restore-folder-2-2", folderAndFile2.parent().id());
        RestoreHandler folderAndFile1_1_1 = createFolderAndFile("test-restore-folder-1-1-1", folderAndFile1_1.parent().id());
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse rootFile = uploadDocument(builder);

        builder = newFileBuilder();
        builder.part("parentFolderId", folderAndFile1_1_1.parent().id().toString());
        UploadResponse file1_1_1_2 = uploadDocument(builder);

        DeleteRequest deleteRequest = new DeleteRequest(List.of(folderAndFile1.parent().id(), folderAndFile2.parent().id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        waitFor(3000L);

        verifyBinIsEmpty();

    }

    private void verifyBinIsEmpty() {
        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        var graphQlRequest = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();
        ListFolderRequest request = new ListFolderRequest(null, null, null, null, null, null, null, null, null, null, null, null
                , null, null, false, null);

        Mono<ClientGraphQlResponse> countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 0L))
                .expectComplete()
                .verify();

        Long count = getWebTestClient().method(HttpMethod.GET).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/count")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Long.class)
                .returnResult().getResponseBody();

        Assertions.assertEquals(0L, count);
    }



}
