package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.SearchMetadataRequest;
import org.openfilz.dms.dto.request.UpdateMetadataRequest;
import org.openfilz.dms.dto.response.DocumentIntegrityRecord;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * C2 — the fingerprint stops living somewhere the API can rewrite.
 * <p>
 * The SHA-256 has always been stored in {@code documents.metadata}, a JSONB field that
 * {@code PATCH /documents/{id}/metadata} exists to modify. A fingerprint anyone can overwrite
 * proves nothing: whoever could alter the bytes could alter the value they are checked against.
 * The load-bearing test here is {@link #tamperingWithTheJsonbDoesNotTouchTheLedger()} — it
 * rewrites the metadata fingerprint through the public API, exactly as an attacker would, and
 * shows the ledger unmoved.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class DocumentIntegrityLedgerIT extends TestContainersBaseConfig {

    private static final ParameterizedTypeReference<List<DocumentIntegrityRecord>> LEDGER =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private DatabaseClient databaseClient;

    public DocumentIntegrityLedgerIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void enableChecksum(DynamicPropertyRegistry registry) {
        registry.add("openfilz.calculate-checksum", () -> Boolean.TRUE);
    }

    private List<DocumentIntegrityRecord> ledger(UploadResponse doc) {
        return webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/integrity", doc.id())
                .exchange().expectStatus().isOk()
                .expectBody(LEDGER).returnResult().getResponseBody();
    }

    private String metadataChecksum(UploadResponse doc) {
        Map<String, Object> metadata = webTestClient.post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", doc.id())
                .body(BodyInserters.fromValue(new SearchMetadataRequest(List.of("sha256"))))
                .exchange().expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();
        Assertions.assertNotNull(metadata);
        return metadata.get("sha256") == null ? null : metadata.get("sha256").toString();
    }

    @Test
    void anUploadIsRecordedInTheLedger() {
        UploadResponse doc = uploadNewFile(null, null);
        Assertions.assertNotNull(doc);

        List<DocumentIntegrityRecord> entries = ledger(doc);

        assertThat(entries).hasSize(1);
        DocumentIntegrityRecord entry = entries.getFirst();
        assertThat(entry.documentId()).isEqualTo(doc.id());
        assertThat(entry.algorithm()).isEqualTo("SHA-256");
        assertThat(entry.hash()).isNotBlank().isEqualTo(metadataChecksum(doc));
        assertThat(entry.recordedAt()).isNotNull();
        assertThat(entry.storagePath()).isNotBlank();
    }

    /**
     * The point of the whole change. The metadata fingerprint is rewritten through the ordinary
     * public API — no privileged access, no SQL — and the ledger still holds the real one.
     */
    @Test
    void tamperingWithTheJsonbDoesNotTouchTheLedger() {
        UploadResponse doc = uploadNewFile(null, null);
        String realHash = ledger(doc).getFirst().hash();

        webTestClient.method(HttpMethod.PATCH)
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", doc.id())
                .body(BodyInserters.fromValue(new UpdateMetadataRequest(Map.of("sha256", "0".repeat(64)))))
                .exchange().expectStatus().is2xxSuccessful();

        assertThat(metadataChecksum(doc))
                .as("the JSONB is still rewritable — that is precisely why it cannot be the reference")
                .isEqualTo("0".repeat(64));
        assertThat(ledger(doc))
                .as("the ledger must be untouched by a metadata write")
                .hasSize(1)
                .first()
                .extracting(DocumentIntegrityRecord::hash)
                .isEqualTo(realHash);
    }

    /**
     * Append-only is enforced by the database, not by the application refraining. Anything holding
     * the app's own credentials — including the app itself, compromised — is refused.
     */
    @Test
    void theLedgerRefusesUpdateAndDelete() {
        UploadResponse doc = uploadNewFile(null, null);
        Assertions.assertNotNull(ledger(doc).getFirst());

        StepVerifier.create(databaseClient.sql(
                        "UPDATE document_integrity SET hash = 'tampered' WHERE document_id = $1")
                        .bind("$1", doc.id()).fetch().rowsUpdated())
                .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("immutable"))
                .verify();

        StepVerifier.create(databaseClient.sql(
                        "DELETE FROM document_integrity WHERE document_id = $1")
                        .bind("$1", doc.id()).fetch().rowsUpdated())
                .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("immutable"))
                .verify();

        assertThat(ledger(doc)).hasSize(1);
    }

    /**
     * A content replacement appends rather than amends: the sequence of fingerprints is the
     * evidence, and a ledger that kept only the latest value would lose the history of changes it
     * exists to attest.
     */
    @Test
    void replacingContentAppendsAnEntry() {
        UploadResponse doc = uploadNewFile(null, null);
        String firstHash = ledger(doc).getFirst().hash();

        webTestClient.put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", doc.id())
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(newFileBuilder("test2.txt").build()))
                .exchange().expectStatus().is2xxSuccessful();

        List<DocumentIntegrityRecord> entries = ledger(doc);
        assertThat(entries).hasSize(2);
        assertThat(entries.getFirst().hash())
                .as("newest first, and the new content has a different fingerprint")
                .isNotEqualTo(firstHash);
        assertThat(entries.getLast().hash())
                .as("the original entry is still there, unchanged")
                .isEqualTo(firstHash);
    }
}
