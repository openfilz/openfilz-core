package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.MetadataKeySuggestionProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.SearchByMetadataRequest;
import org.openfilz.dms.dto.response.MetadataKeySuggestion;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Metadata search with wildcard values (REST {@code ids-by-metadata}, GraphQL {@code listAllFolder})
 * and the metadata key suggestions ({@code GET /documents/metadata-keys}). Every test uses keys of
 * its own, so the shared database never makes two tests see each other's documents.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class MetadataPatternSearchIT extends TestContainersBaseConfig {

    private static final String IDS_BY_METADATA = RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata";
    private static final String METADATA_KEYS = RestApiVersion.API_PREFIX + "/documents/metadata-keys";

    private final MetadataKeySuggestionProperties suggestionProperties;
    private HttpGraphQlClient graphQlClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.soft-delete.active", () -> true);
        // every call recomputes the list, so a test sees the documents it just uploaded
        registry.add("openfilz.metadata.key-suggestions.cache-ttl", () -> "0s");
        // the database is shared with the other suites: list every key, not the 200 most frequent
        registry.add("openfilz.metadata.key-suggestions.max-keys", () -> 100_000);
    }

    MetadataPatternSearchIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder,
                     MetadataKeySuggestionProperties suggestionProperties) {
        super(webTestClient, customJacksonJsonEncoder);
        this.suggestionProperties = suggestionProperties;
    }

    // ==================== wildcards, REST ====================

    @Test
    void whenPrefixSuffixInfixPatterns_thenMatchingDocuments() {
        String key = newKey();
        UUID beta1 = upload(Map.of(key, "beta-1"));
        UUID beta2 = upload(Map.of(key, "beta-2"));
        UUID gamma = upload(Map.of(key, "gamma-beta"));

        Assertions.assertEquals(Set.of(beta1, beta2), search(Map.of(key, "beta%")));
        Assertions.assertEquals(Set.of(beta1, beta2), search(Map.of(key, "beta*")));
        Assertions.assertEquals(Set.of(gamma), search(Map.of(key, "%-beta")));
        Assertions.assertEquals(Set.of(beta1, beta2, gamma), search(Map.of(key, "%beta%")));
        // ? is one character inside a pattern...
        Assertions.assertEquals(Set.of(beta1, beta2), search(Map.of(key, "%beta-?")));
        Assertions.assertEquals(Set.of(), search(Map.of(key, "%beta-??")));
        // ...but a value without % or * stays an exact match, ? included
        Assertions.assertEquals(Set.of(), search(Map.of(key, "beta-?")));
    }

    @Test
    void whenPatternInOtherCase_thenMatchesCaseInsensitively() {
        String key = newKey();
        UUID doc = upload(Map.of(key, "Beta-Release"));

        Assertions.assertEquals(Set.of(doc), search(Map.of(key, "BETA%")));
        // exact values match case-insensitively too
        Assertions.assertEquals(Set.of(doc), search(Map.of(key, "beta-release")));
        Assertions.assertEquals(Set.of(doc), search(Map.of(key, "Beta-Release")));
        Assertions.assertEquals(Set.of(), search(Map.of(key, "beta-releas")));
    }

    @Test
    void whenKeyInOtherCase_thenMatchesCaseInsensitively() {
        String key = newKey();
        UUID lower = upload(Map.of(key, "Acme", "n", 7));
        UUID upper = upload(Map.of(key.toUpperCase(), "ACME"));
        upload(Map.of(key, "Other"));

        Assertions.assertEquals(Set.of(lower, upper), search(Map.of(key, "acme")));
        Assertions.assertEquals(Set.of(lower, upper), search(Map.of(key.toUpperCase(), "aCmE")));
        Assertions.assertEquals(Set.of(lower, upper), search(Map.of(key.substring(0, 1).toUpperCase() + key.substring(1), "ac%")));
        Assertions.assertEquals(Set.of(lower), search(Map.of(key.toUpperCase(), "ACME", "N", 7)));
        Assertions.assertEquals(Set.of(), search(Map.of(key, "acme", "n", 8)));
    }

    @Test
    void whenPatternHoldsUnderscoreOrBackslash_thenTheyAreLiteral() {
        String key = newKey();
        UUID underscore = upload(Map.of(key, "INV_2024-01"));
        upload(Map.of(key, "INVX2024-01"));
        UUID backslash = upload(Map.of(key, "C:\\docs\\a"));

        Assertions.assertEquals(Set.of(underscore), search(Map.of(key, "INV_%")));
        Assertions.assertEquals(Set.of(backslash), search(Map.of(key, "C:\\docs\\%")));
    }

    @Test
    void whenBareWildcard_thenEveryDocumentCarryingTheKey() {
        String key = newKey();
        UUID text = upload(Map.of(key, "anything"));
        UUID number = upload(Map.of(key, 42));
        upload(Map.of(newKey(), "other key"));

        Assertions.assertEquals(Set.of(text, number), search(Map.of(key, "%")));
        Assertions.assertEquals(Set.of(text, number), search(Map.of(key, "*")));
    }

    @Test
    void whenPatternOnANumber_thenItsTextIsMatched() {
        String key = newKey();
        UUID year = upload(Map.of(key, 2024));
        upload(Map.of(key, 1999));

        Assertions.assertEquals(Set.of(year), search(Map.of(key, "20%")));
    }

    @Test
    void whenExactAndPatternCriteriaMixed_thenAllMustHold() {
        String project = newKey();
        String status = newKey();
        UUID match = upload(Map.of(project, "beta-1", status, "approved"));
        upload(Map.of(project, "beta-2", status, "draft"));
        upload(Map.of(project, "alpha", status, "approved"));

        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put(project, "beta%");
        criteria.put(status, "approved");
        Assertions.assertEquals(Set.of(match), search(criteria));

        // two patterns at once
        criteria.put(status, "app%");
        Assertions.assertEquals(Set.of(match), search(criteria));
    }

    @Test
    void whenDocumentDeleted_thenNoLongerMatched() {
        String key = newKey();
        UUID kept = upload(Map.of(key, "value-1"));
        UUID deleted = upload(Map.of(key, "value-2"));
        deleteFile(deleted);

        Assertions.assertEquals(Set.of(kept), search(Map.of(key, "value%")));
        Assertions.assertEquals(Set.of(), search(Map.of(key, "value-2")));
    }

    @Test
    void whenCriteriaExceedTheLimits_thenBadRequest() {
        Map<String, Object> tooMany = new HashMap<>();
        for (int i = 0; i < 11; i++) {
            tooMany.put("k" + i, "v");
        }
        searchExchange(tooMany).expectStatus().isBadRequest();
        searchExchange(Map.of("k".repeat(101), "v")).expectStatus().isBadRequest();
        searchExchange(Map.of("k", "%" + "x".repeat(200))).expectStatus().isBadRequest();
    }

    // ==================== wildcards, GraphQL ====================

    @Test
    void whenListAllFolderWithMetadataPattern_thenMatchingDocuments() {
        String key = newKey();
        UUID beta = upload(Map.of(key, "beta-1"));
        upload(Map.of(key, "alpha-1"));

        Map<String, Object> request = new HashMap<>();
        request.put("metadata", Map.of(key, "BETA*"));
        request.put("pageInfo", Map.of("sortBy", "name", "sortOrder", "ASC", "pageNumber", 1, "pageSize", 100));

        ClientGraphQlResponse response = graphQl()
                .document("""
                        query listAllFolder($request:ListFolderRequest!) {
                            listAllFolder(request:$request) { id name }
                        }""")
                .variable("request", request)
                .execute()
                .block();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.getErrors().isEmpty(), () -> response.getErrors().toString());
        List<Map<String, Object>> rows = response.field("listAllFolder").getValue();
        Assertions.assertNotNull(rows);
        Assertions.assertEquals(Set.of(beta.toString()),
                rows.stream().map(r -> (String) r.get("id")).collect(Collectors.toSet()));
    }

    // ==================== key suggestions ====================

    @Test
    void whenListingMetadataKeys_thenMostFrequentFirstWithCounts() {
        String frequent = newKey();
        String rare = newKey();
        upload(Map.of(frequent, "a", rare, "x"));
        upload(Map.of(frequent, "b"));
        upload(Map.of(frequent, "c"));

        List<MetadataKeySuggestion> keys = listKeys();

        MetadataKeySuggestion frequentKey = find(keys, frequent);
        MetadataKeySuggestion rareKey = find(keys, rare);
        Assertions.assertNotNull(frequentKey);
        Assertions.assertNotNull(rareKey);
        Assertions.assertEquals(3, frequentKey.count());
        Assertions.assertEquals(1, rareKey.count());
        Assertions.assertTrue(keys.indexOf(frequentKey) < keys.indexOf(rareKey));
        for (int i = 1; i < keys.size(); i++) {
            Assertions.assertTrue(keys.get(i - 1).count() >= keys.get(i).count(), "sorted by frequency");
        }
    }

    @Test
    void whenKeyOnlyOnDeletedDocuments_thenNotSuggested() {
        String gone = newKey();
        UUID doc = upload(Map.of(gone, "v"));
        Assertions.assertNotNull(find(listKeys(), gone));

        deleteFile(doc);

        Assertions.assertNull(find(listKeys(), gone));
    }

    @Test
    void whenPlatformKey_thenNeverSuggested() {
        upload(Map.of("sha256", "deadbeef", newKey(), "v"));

        Assertions.assertNull(find(listKeys(), "sha256"));
    }

    @Test
    void whenKeySuggestionsDisabled_thenNotFound() {
        suggestionProperties.setEnabled(false);
        try {
            getWebTestClient().get().uri(METADATA_KEYS).exchange().expectStatus().isNotFound();
        } finally {
            suggestionProperties.setEnabled(true);
        }
        getWebTestClient().get().uri(METADATA_KEYS).exchange().expectStatus().isOk();
    }

    @Test
    void whenMaxKeysIsSmall_thenTheListIsCapped() {
        upload(Map.of(newKey(), "1", newKey(), "2", newKey(), "3"));
        int max = suggestionProperties.getMaxKeys();
        suggestionProperties.setMaxKeys(2);
        try {
            Assertions.assertEquals(2, listKeys().size());
        } finally {
            suggestionProperties.setMaxKeys(max);
        }
    }

    // ==================== helpers ====================

    private static String newKey() {
        return "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private UUID upload(Map<String, Object> metadata) {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", metadata);
        UploadResponse response = uploadDocument(builder);
        Assertions.assertNotNull(response);
        return response.id();
    }

    private void deleteFile(UUID id) {
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(new DeleteRequest(List.of(id))))
                .exchange()
                .expectStatus().isNoContent();
    }

    private WebTestClient.ResponseSpec searchExchange(Map<String, Object> criteria) {
        return getWebTestClient().post().uri(IDS_BY_METADATA)
                .body(BodyInserters.fromValue(new SearchByMetadataRequest(null, null, null, null, criteria)))
                .exchange();
    }

    private Set<UUID> search(Map<String, Object> criteria) {
        List<UUID> ids = searchExchange(criteria)
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {
                })
                .returnResult().getResponseBody();
        Assertions.assertNotNull(ids);
        return Set.copyOf(ids);
    }

    private List<MetadataKeySuggestion> listKeys() {
        List<MetadataKeySuggestion> keys = getWebTestClient().get().uri(METADATA_KEYS)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<MetadataKeySuggestion>>() {
                })
                .returnResult().getResponseBody();
        Assertions.assertNotNull(keys);
        return keys;
    }

    private static MetadataKeySuggestion find(List<MetadataKeySuggestion> keys, String key) {
        return keys.stream().filter(k -> k.key().equals(key)).findFirst().orElse(null);
    }

    private HttpGraphQlClient graphQl() {
        if (graphQlClient == null) {
            graphQlClient = newGraphQlClient();
        }
        return graphQlClient;
    }
}
