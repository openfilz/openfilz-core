package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.e2e.util.PdfLoremGeneratorStreaming;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@AutoConfigureWebTestClient(timeout = "30000")//10 seconds
public class FullTextDefaultSearchIT extends TestContainersBaseConfig {


    protected HttpGraphQlClient graphQlHttpClient;

    public FullTextDefaultSearchIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @Test
    void testSuggestionsRestAPI() throws Exception {
        String f0 = "Folder for " +getSuggestionQuery1();
        String f1 = "a sample data file of december.pdf";
        String f2 = "a sample data file of november.pdf";
        String f3 = "Meeting with the boss.pdf";

        CreateFolderRequest createFolderRequest = new CreateFolderRequest(f0, null);

        FolderResponse r0 = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(r0);

        PdfLoremGeneratorStreaming.generate("target/test-classes/" + f1, 1L);
        MultipartBodyBuilder builder = newFileBuilder(f1);
        UploadResponse r1 = getUploadResponse(builder);

        PdfLoremGeneratorStreaming.generate("target/test-classes/" + f2, 1L);
        builder = newFileBuilder(f2);
        UploadResponse r2 = getUploadResponse(builder);

        PdfLoremGeneratorStreaming.generate("target/test-classes/" + f3, 1L);
        builder = newFileBuilder(f3);
        UploadResponse r3 = getUploadResponse(builder);

        waitFor(3000);

        List<Suggest> suggestions = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                                .queryParam("q", getSuggestionQuery1())
                                .build())
                .exchange()
                .expectBody(new ParameterizedTypeReference<List<Suggest>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(suggestions);
        Assertions.assertEquals(3, suggestions.size());

        suggestions = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                                .queryParam("q", getSuggestionQuery2())
                                .build())
                .exchange()
                .expectBody(new ParameterizedTypeReference<List<Suggest>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(suggestions);
        Assertions.assertEquals(1, suggestions.size());

        suggestions = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                                .queryParam("q", "")
                                .build())
                .exchange()
                .expectBody(new ParameterizedTypeReference<List<Suggest>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(suggestions);
        Assertions.assertEquals(0, suggestions.size());

        DeleteRequest deleteRequest = new DeleteRequest(List.of(r1.id(), r2.id(),  r3.id()));
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        deleteRequest = new DeleteRequest(List.of(r0.id()));
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        waitFor(3000);

        suggestions = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                                .queryParam("q", getSuggestionQuery1())
                                .build())
                .exchange()
                .expectBody(new ParameterizedTypeReference<List<Suggest>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(suggestions);
        Assertions.assertEquals(0, suggestions.size());
    }

    protected String getSuggestionQuery1() {
        return "sample data file";
    }

    protected String getSuggestionQuery2() {
        return "meeting";
    }



    @Test
    void testSearchGraphQL() throws Exception {

        String f0 = "pdf-example.pdf";
        String f1 = "a wonderful sunny day of september.pdf";
        String f2 = "a wonderful sunny day of october.pdf";
        String f3 = "Call with another guy - 2025.pdf";

        MultipartBodyBuilder builder = newFileBuilder(f0);
        builder.part("metadata", Map.of("owner", "OpenFilz"));
        UploadResponse r0 = getUploadResponse(builder);

        PdfLoremGeneratorStreaming.generate("target/test-classes/" + f1, 1L);
        builder = newFileBuilder(f1);
        builder.part("metadata", Map.of("owner", "OpenFilz"));
        UploadResponse r1 = getUploadResponse(builder);

        PdfLoremGeneratorStreaming.generate("target/test-classes/" + f2, 1L);
        builder = newFileBuilder(f2);
        builder.part("metadata", Map.of("owner", "OpenFilz"));
        UploadResponse r2 = getUploadResponse(builder);

        PdfLoremGeneratorStreaming.generate("target/test-classes/" + f3, 1L);
        builder = newFileBuilder(f3);
        builder.part("metadata", Map.of("owner", "Nobody"));
        UploadResponse r3 = getUploadResponse(builder);

        waitFor(3000);

        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();

        var graphQlRequest = """
                query {
                  searchDocuments(
                    query: "$$QUERY$$",
                    filters: [
                      { field: "metadata.owner", value: "OpenFilz" }
                    ],
                    sort: { field: "updatedAt", order: DESC },
                    page: 1,
                    size: 20
                  ) {
                    totalHits
                    documents {
                      id
                      name
                      extension
                      updatedAt
                      updatedBy
                    }
                  }
                }""".replace("$$QUERY$$", getSearchQuery()).trim();

        Mono<ClientGraphQlResponse> response = httpGraphQlClient
                .document(graphQlRequest)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc->{
                    Map<String, Object> searchDocuments = (Map<String, Object>) ((Map<String, Object>) doc.getData()).get("searchDocuments");
                    List<Map<String, String>> documents = (List<Map<String, String>>) searchDocuments.get("documents");
                    return  searchDocuments.get("totalHits").toString().equals("2")
                            && documents.get(0).get("id").equals(r2.id().toString())
                            && documents.get(1).get("id").equals(r1.id().toString())
                            && documents.get(0).get("extension").equals("pdf")
                            && documents.get(1).get("extension").equals("pdf")
                            && documents.get(0).get("updatedBy").equals("anonymousUser")
                            && documents.get(1).get("updatedBy").equals("anonymousUser")
                            && documents.get(0).get("name").equals(f2)
                            && documents.get(1).get("name").equals(f1);
                })
                .expectComplete()
                .verify();




        DeleteRequest deleteRequest = new DeleteRequest(List.of(r0.id(), r1.id(), r2.id(),  r3.id()));
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        waitFor(3000);

    }

    protected String getSearchQuery() {
        return "wonderful sunny day";
    }


    protected void waitFor(long timeout) throws InterruptedException {
        CountDownLatch latch2 = new CountDownLatch(1);
        new Thread(() -> {
            log.info("Waiting 3 seconds...");
            try { Thread.sleep(timeout); } catch (InterruptedException ignored) {}
            latch2.countDown();
        }).start();
        latch2.await();
    }

    protected HttpGraphQlClient getGraphQlHttpClient() {
        if(graphQlHttpClient == null) {
            graphQlHttpClient = newGraphQlClient();
        }
        return graphQlHttpClient;
    }

}
