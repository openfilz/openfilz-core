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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class FullTextDefaultSearchIT extends TestContainersBaseConfig {


    protected HttpGraphQlClient graphQlHttpClient;

    public FullTextDefaultSearchIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
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

        // Poll instead of a fixed sleep: full-text indexing is async with an unbounded
        // settle time — polling resolves fast on a quiet machine and tolerates a slow CI.
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<Suggest> indexedSuggestions = getWebTestClient().get().uri(uri ->
                                    uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                                            .queryParam("q", getSuggestionQuery1())
                                            .build())
                            .exchange()
                            .expectBody(new ParameterizedTypeReference<List<Suggest>>() {
                            })
                            .returnResult().getResponseBody();

                    Assertions.assertNotNull(indexedSuggestions);
                    Assertions.assertEquals(3, indexedSuggestions.size());
                });

        // The third file can still be indexing once the first query sees its three hits: poll this one too
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<Suggest> meeting = getWebTestClient().get().uri(uri ->
                                    uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                                            .queryParam("q", getSuggestionQuery2())
                                            .build())
                            .exchange()
                            .expectBody(new ParameterizedTypeReference<List<Suggest>>() {
                            })
                            .returnResult().getResponseBody();

                    Assertions.assertNotNull(meeting);
                    Assertions.assertEquals(1, meeting.size());
                });

        List<Suggest> suggestions = getWebTestClient().get().uri(uri ->
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

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    List<Suggest> deletedSuggestions = getWebTestClient().get().uri(uri ->
                                    uri.path(RestApiVersion.API_PREFIX + "/suggestions")
                                            .queryParam("q", getSuggestionQuery1())
                                            .build())
                            .exchange()
                            .expectBody(new ParameterizedTypeReference<List<Suggest>>() {
                            })
                            .returnResult().getResponseBody();

                    Assertions.assertNotNull(deletedSuggestions);
                    Assertions.assertEquals(0, deletedSuggestions.size());
                });
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

        // Poll instead of a fixed sleep: full-text indexing is async with an unbounded
        // settle time — the GraphQL query is read-only so re-running it is safe.
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
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
                                        && documents.get(0).get("updatedBy").equals(getUsername())
                                        && documents.get(1).get("updatedBy").equals(getUsername())
                                        && documents.get(0).get("name").equals(f2)
                                        && documents.get(1).get("name").equals(f1);
                            })
                            .expectComplete()
                            .verify();
                });




        DeleteRequest deleteRequest = new DeleteRequest(List.of(r0.id(), r1.id(), r2.id(),  r3.id()));
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        waitFor(3000);

    }

    // ---------------------------------------------------------------- filters + sorts of searchDocuments

    /** The four documents of a filter / sort scenario: a folder and three files, all named after {@code token}. */
    protected record SearchFixture(String token, FolderResponse folder, UploadResponse pdf, UploadResponse png, UploadResponse txt) {
        List<UUID> fileIds() {
            return List.of(pdf.id(), png.id(), txt.id());
        }
    }

    /** Names: "<token> folder", "<token> alpha.pdf" (131 KB), "<token> beta.png" (2.7 KB), "<token> gamma.txt" (74 B). */
    protected SearchFixture createSearchFixture() {
        String token = "qz" + randomLetters(10);
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest(token + " folder", null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
        UploadResponse pdf = uploadAs("pdf-example.pdf", token + " alpha.pdf", MediaType.APPLICATION_PDF);
        UploadResponse png = uploadAs("test-image.png", token + " beta.png", MediaType.IMAGE_PNG);
        UploadResponse txt = uploadAs("test.txt", token + " gamma.txt", MediaType.TEXT_PLAIN);
        return new SearchFixture(token, folder, pdf, png, txt);
    }

    /**
     * Letters only (the name analyzer splits on digits) and drawn from the whole alphabet: tokens built
     * from hex with every digit turned into "g" looked alike ("qzggggggeggg" / "qzggegggcggg"), and the
     * fuzzy name match of the OpenSearch path then returned another fixture's files.
     */
    protected static String randomLetters(int length) {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('a' + random.nextInt(26)));
        }
        return letters.toString();
    }

    protected UploadResponse uploadAs(String resource, String filename, MediaType contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource(resource)).filename(filename).contentType(contentType);
        return getUploadResponse(builder);
    }

    protected void deleteSearchFixture(SearchFixture fixture) {
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(new DeleteRequest(fixture.fileIds())))
                .exchange()
                .expectStatus().isNoContent();
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new DeleteRequest(List.of(fixture.folder().id()))))
                .exchange()
                .expectStatus().isNoContent();
    }

    /** One page of hits, as returned by the API. */
    protected record SearchPage(long totalHits, List<Map<String, Object>> documents) {
        List<String> names() {
            return documents.stream().map(d -> (String) d.get("name")).toList();
        }
    }

    private static final String SEARCH_DOCUMENTS = """
            query search($query: String, $filters: [FilterInput!], $sort: SortInput) {
              searchDocuments(query: $query, filters: $filters, sort: $sort, page: 1, size: 20) {
                totalHits
                documents { id name extension contentType size createdBy contentSnippet }
              }
            }""";

    protected SearchPage search(String query, List<Map<String, String>> filters, Map<String, String> sort) {
        ClientGraphQlResponse response = getGraphQlHttpClient()
                .document(SEARCH_DOCUMENTS)
                .variable("query", query)
                .variable("filters", filters)
                .variable("sort", sort)
                .execute()
                .block(Duration.ofSeconds(30));
        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.getErrors().isEmpty(), () -> "GraphQL errors: " + response.getErrors());
        Map<String, Object> result = response.field("searchDocuments").toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});
        Assertions.assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
        return new SearchPage(((Number) result.get("totalHits")).longValue(), documents);
    }

    protected static Map<String, String> filter(String field, String value) {
        return Map.of("field", field, "value", value);
    }

    protected static Map<String, String> sort(String field, String order) {
        return Map.of("field", field, "order", order);
    }

    /** Waits until the four documents of the fixture are searchable (full-text indexing is asynchronous). */
    protected void awaitSearchable(SearchFixture fixture) {
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> Assertions.assertEquals(4, search(fixture.token(), List.of(), null).totalHits()));
    }

    @Test
    void testSearchFilters() {
        SearchFixture fx = createSearchFixture();
        try {
            awaitSearchable(fx);
            String t = fx.token();

            Assertions.assertEquals(List.of(t + " folder"), search(t, List.of(filter("type", "FOLDER")), null).names());
            Assertions.assertEquals(3, search(t, List.of(filter("type", "FILE")), null).totalHits());

            Assertions.assertEquals(List.of(t + " beta.png"), search(t, List.of(filter("contentType", "image/%")), null).names());
            Assertions.assertEquals(
                    Set.of(t + " alpha.pdf", t + " gamma.txt"),
                    Set.copyOf(search(t, List.of(filter("contentType", "application/pdf, text/%")), null).names()));
            Assertions.assertEquals(List.of(t + " beta.png"),
                    search(t, List.of(filter("type", "FILE"), filter("contentType", "image/%")), null).names());

            Assertions.assertEquals(4, search(t, List.of(filter("createdBy", getUsername())), null).totalHits());
            Assertions.assertEquals(0, search(t, List.of(filter("createdBy", "nobody@example.com")), null).totalHits());

            String yesterday = OffsetDateTime.now().minusDays(1).toString();
            String tomorrow = OffsetDateTime.now().plusDays(1).toString();
            Assertions.assertEquals(4, search(t, List.of(filter("updatedAtAfter", yesterday)), null).totalHits());
            Assertions.assertEquals(0, search(t, List.of(filter("updatedAtAfter", tomorrow)), null).totalHits());
            Assertions.assertEquals(4, search(t, List.of(filter("updatedAtBefore", tomorrow)), null).totalHits());
            Assertions.assertEquals(4, search(t, List.of(filter("createdAtAfter", yesterday)), null).totalHits());
            Assertions.assertEquals(0, search(t, List.of(filter("createdAtBefore", yesterday)), null).totalHits());
        } finally {
            deleteSearchFixture(fx);
        }
    }

    @Test
    void testSearchSorts() {
        SearchFixture fx = createSearchFixture();
        try {
            awaitSearchable(fx);
            String t = fx.token();
            List<Map<String, String>> files = List.of(filter("type", "FILE"));
            List<String> byName = List.of(t + " alpha.pdf", t + " beta.png", t + " gamma.txt");
            List<String> bySize = List.of(t + " gamma.txt", t + " beta.png", t + " alpha.pdf");

            Assertions.assertEquals(byName, search(t, files, sort("name", "ASC")).names());
            Assertions.assertEquals(byName.reversed(), search(t, files, sort("name", "DESC")).names());
            Assertions.assertEquals(bySize, search(t, files, sort("size", "ASC")).names());
            Assertions.assertEquals(bySize.reversed(), search(t, files, sort("size", "DESC")).names());
            // Uploaded in name order
            Assertions.assertEquals(byName, search(t, files, sort("createdAt", "ASC")).names());
            Assertions.assertEquals(byName.reversed(), search(t, files, sort("updatedAt", "DESC")).names());
            // One owner, one type: the sort must be accepted and keep every hit
            for (String field : sortFieldsWithoutOrderCheck()) {
                Assertions.assertEquals(4, search(t, List.of(), sort(field, "ASC")).totalHits(), "sort on " + field);
            }
        } finally {
            deleteSearchFixture(fx);
        }
    }

    @Test
    void testSearchMetadataPatternFilters() {
        String t = "qm" + randomLetters(10);
        List<UUID> ids = new java.util.ArrayList<>();
        for (String project : List.of("Beta-1", "beta-2", "gamma")) {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ClassPathResource("test.txt")).filename(t + " " + project + ".txt").contentType(MediaType.TEXT_PLAIN);
            builder.part("metadata", Map.of("project", project));
            ids.add(getUploadResponse(builder).id());
        }
        try {
            await().atMost(Duration.ofSeconds(45))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> Assertions.assertEquals(3, search(t, List.of(), null).totalHits()));

            Assertions.assertEquals(Set.of(t + " Beta-1.txt", t + " beta-2.txt"),
                    Set.copyOf(search(t, List.of(filter("metadata.project", "BETA%")), null).names()));
            Assertions.assertEquals(Set.of(t + " Beta-1.txt", t + " beta-2.txt"),
                    Set.copyOf(search(t, List.of(filter("metadata.project", "*eta-?")), null).names()));
            Assertions.assertEquals(List.of(t + " gamma.txt"),
                    search(t, List.of(filter("metadata.project", "%mm%")), null).names());
            Assertions.assertEquals(3, search(t, List.of(filter("metadata.project", "*")), null).totalHits());
            // exact values keep the exact match
            Assertions.assertEquals(List.of(t + " gamma.txt"),
                    search(t, List.of(filter("metadata.project", "gamma")), null).names());
        } finally {
            getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                    .body(BodyInserters.fromValue(new DeleteRequest(ids)))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    /**
     * Values typed as text find booleans and numbers. Keys of their own, so the index maps them
     * from these documents only ({@code boolean} / {@code long}, no keyword sub-field).
     */
    @Test
    void testSearchMetadataTypedValueFilters() {
        String t = "qv" + randomLetters(10);
        String flag = "flag" + randomLetters(8);
        String amount = "amount" + randomLetters(8);
        List<UUID> ids = new java.util.ArrayList<>();
        for (Map<String, Object> metadata : List.of(Map.<String, Object>of(flag, true, amount, 42),
                Map.<String, Object>of(flag, false, amount, 7))) {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ClassPathResource("test.txt")).filename(t + " " + metadata.get(amount) + ".txt").contentType(MediaType.TEXT_PLAIN);
            builder.part("metadata", metadata);
            ids.add(getUploadResponse(builder).id());
        }
        try {
            await().atMost(Duration.ofSeconds(45))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> Assertions.assertEquals(2, search(t, List.of(), null).totalHits()));

            Assertions.assertEquals(List.of(t + " 42.txt"),
                    search(t, List.of(filter("metadata." + flag, "true")), null).names());
            Assertions.assertEquals(List.of(t + " 7.txt"),
                    search(t, List.of(filter("metadata." + flag, "FALSE")), null).names());
            Assertions.assertEquals(List.of(t + " 42.txt"),
                    search(t, List.of(filter("metadata." + amount, "42")), null).names());
            Assertions.assertEquals(List.of(t + " 42.txt"),
                    search(t, List.of(filter("metadata." + amount, "42.0")), null).names());
            // a value of another type finds nothing, and does not fail the search
            Assertions.assertEquals(0, search(t, List.of(filter("metadata." + flag, "42")), null).totalHits());
            Assertions.assertEquals(0, search(t, List.of(filter("metadata." + amount, "true")), null).totalHits());
            Assertions.assertEquals(0, search(t, List.of(filter("metadata." + amount, "4.2")), null).totalHits());
            // bare wildcard on a boolean key
            Assertions.assertEquals(2, search(t, List.of(filter("metadata." + flag, "%")), null).totalHits());
        } finally {
            getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                    .body(BodyInserters.fromValue(new DeleteRequest(ids)))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    @Test
    void testSearchNameWithUnderscore() {
        String t = "qu" + randomLetters(10);
        List<UUID> ids = new java.util.ArrayList<>();
        // "_" must be literal: as a LIKE wildcard it would also match the second name
        for (String name : List.of(t + "_x1.txt", t + "ax1.txt")) {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ClassPathResource("test.txt")).filename(name).contentType(MediaType.TEXT_PLAIN);
            ids.add(getUploadResponse(builder).id());
        }
        try {
            await().atMost(Duration.ofSeconds(45))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> assertNameHit(t + "_x1.txt", search(t + "_x1", List.of(), null)));
            assertNameHit(t + "_x1.txt", search(t + "_x1.txt", List.of(), null));
            assertNameHit(t + "_x1.txt", search(t.toUpperCase() + "_X1", List.of(filter("type", "FILE")), null));
        } finally {
            getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                    .body(BodyInserters.fromValue(new DeleteRequest(ids)))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    /**
     * The database matches the name exactly: the only hit. The index also matches names fuzzily
     * (one typo away), so there it is the best hit.
     */
    protected void assertNameHit(String expected, SearchPage page) {
        Assertions.assertEquals(List.of(expected), page.names());
    }

    @Test
    void testSearchMetadataExactValueIgnoresCase() {
        String t = "qc" + randomLetters(10);
        List<UUID> ids = new java.util.ArrayList<>();
        List<Map<String, Object>> metadata = List.of(Map.of("Client", "ACME Corp"), Map.of("client", "acme corp"), Map.of("client", "Other"));
        for (int i = 0; i < metadata.size(); i++) {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ClassPathResource("test.txt")).filename(t + " doc" + i + ".txt").contentType(MediaType.TEXT_PLAIN);
            builder.part("metadata", metadata.get(i));
            ids.add(getUploadResponse(builder).id());
        }
        try {
            await().atMost(Duration.ofSeconds(45))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> Assertions.assertEquals(3, search(t, List.of(), null).totalHits()));

            Set<String> acme = Set.of(t + " doc0.txt", t + " doc1.txt");
            Assertions.assertEquals(acme, Set.copyOf(search(t, List.of(filter("metadata.client", "Acme Corp")), null).names()));
            Assertions.assertEquals(acme, Set.copyOf(search(t, List.of(filter("metadata.CLIENT", "ACME CORP")), null).names()));
            Assertions.assertEquals(acme, Set.copyOf(search(t, List.of(filter("metadata.Client", "acme%")), null).names()));
            Assertions.assertEquals(List.of(t + " doc2.txt"), search(t, List.of(filter("metadata.CLIENT", "other")), null).names());
            Assertions.assertEquals(0, search(t, List.of(filter("metadata.client", "acme")), null).totalHits());
        } finally {
            getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                    .body(BodyInserters.fromValue(new DeleteRequest(ids)))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    /** Sort fields both back ends accept, whose order the fixture cannot tell apart. */
    protected List<String> sortFieldsWithoutOrderCheck() {
        return List.of("type", "createdBy", "updatedBy");
    }

    protected String getUsername() {
        return "anonymousUser";
    }

    protected String getSearchQuery() {
        return "wonderful sunny day";
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

    protected HttpGraphQlClient getGraphQlHttpClient() {
        if(graphQlHttpClient == null) {
            graphQlHttpClient = newGraphQlClient();
        }
        return graphQlHttpClient;
    }

}
