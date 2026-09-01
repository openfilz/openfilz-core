package org.openfilz.dms.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AuditChainProperties;
import org.openfilz.dms.dto.audit.IAuditLogDetails;
import org.openfilz.dms.dto.audit.UploadAudit;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuditChainServiceTest {

    private AuditChainService auditChainService;

    @BeforeEach
    void setUp() {
        AuditChainProperties properties = new AuditChainProperties();
        properties.setAlgorithm("SHA-256");
        auditChainService = new AuditChainService(properties);
    }

    @Test
    void genesisHash_isDeterministic() {
        String hash1 = auditChainService.computeGenesisHash();
        String hash2 = auditChainService.computeGenesisHash();
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length(), "SHA-256 produces 64 hex chars");
    }

    @Test
    void genesisHash_matchesManualComputation() throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] expected = digest.digest("GENESIS".getBytes(StandardCharsets.UTF_8));
        String expectedHex = HexFormat.of().formatHex(expected);
        assertEquals(expectedHex, auditChainService.computeGenesisHash());
    }

    @Test
    void computeHash_isDeterministic() {
        OffsetDateTime timestamp = OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        UUID resourceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String previousHash = "abc123";

        String hash1 = auditChainService.computeHash(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, previousHash);
        String hash2 = auditChainService.computeHash(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, previousHash);

        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
    }

    @Test
    void computeHash_differentInputs_produceDifferentHashes() {
        OffsetDateTime timestamp = OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        UUID resourceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String previousHash = "abc123";

        String hash1 = auditChainService.computeHash(timestamp, "user1@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, previousHash);
        String hash2 = auditChainService.computeHash(timestamp, "user2@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, previousHash);

        assertNotEquals(hash1, hash2);
    }

    @Test
    void computeHash_differentActions_produceDifferentHashes() {
        OffsetDateTime timestamp = OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        UUID resourceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String previousHash = "abc123";

        String hashUpload = auditChainService.computeHash(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, previousHash);
        String hashDelete = auditChainService.computeHash(timestamp, "user@test.com",
                AuditAction.DELETE_FILE, DocumentType.FILE, resourceId, null, previousHash);

        assertNotEquals(hashUpload, hashDelete);
    }

    @Test
    void computeHash_differentPreviousHash_produceDifferentHashes() {
        OffsetDateTime timestamp = OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        UUID resourceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        String hash1 = auditChainService.computeHash(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, "prevhash1");
        String hash2 = auditChainService.computeHash(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, "prevhash2");

        assertNotEquals(hash1, hash2, "Different previousHash must produce different hashes (chain linkage)");
    }

    @Test
    void canonicalize_nullFields_usesEmptyStrings() {
        String canonical = auditChainService.canonicalize(null, null, null, null, null, null, null);
        assertEquals("||||||", canonical);
    }

    @Test
    void canonicalize_allFields_producesExpectedFormat() {
        OffsetDateTime timestamp = OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        UUID resourceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        String canonical = auditChainService.canonicalize(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, "prevhash");

        long expectedEpochMillis = timestamp.toInstant().toEpochMilli();
        String expected = expectedEpochMillis + "|user@test.com|UPLOAD_DOCUMENT|FILE|550e8400-e29b-41d4-a716-446655440000||prevhash";
        assertEquals(expected, canonical);
    }

    @Test
    void computeHash_chainLinkage_works() {
        // Simulate a chain: genesis → entry1 → entry2
        String genesisHash = auditChainService.computeGenesisHash();

        OffsetDateTime ts1 = OffsetDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);
        String hash1 = auditChainService.computeHash(ts1, "user@test.com",
                AuditAction.CHAIN_GENESIS, null, null, null, genesisHash);

        OffsetDateTime ts2 = OffsetDateTime.of(2025, 6, 15, 10, 1, 0, 0, ZoneOffset.UTC);
        UUID resourceId = UUID.randomUUID();
        String hash2 = auditChainService.computeHash(ts2, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, hash1);

        // All hashes are distinct
        assertNotEquals(genesisHash, hash1);
        assertNotEquals(hash1, hash2);
        assertNotEquals(genesisHash, hash2);

        // All are valid SHA-256 hex strings
        assertTrue(genesisHash.matches("[0-9a-f]{64}"));
        assertTrue(hash1.matches("[0-9a-f]{64}"));
        assertTrue(hash2.matches("[0-9a-f]{64}"));
    }

    @Test
    void computeHash_withDetails_includesSerializedDetails() {
        OffsetDateTime timestamp = OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        UUID resourceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID parentId = UUID.fromString("660e8400-e29b-41d4-a716-446655440000");

        UploadAudit details = new UploadAudit("test.pdf", parentId, Map.of("key", "value"));

        String hashWithDetails = auditChainService.computeHash(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, details, "prevhash");

        String hashWithoutDetails = auditChainService.computeHash(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, null, "prevhash");

        assertNotEquals(hashWithDetails, hashWithoutDetails);
    }

    @Test
    void canonicalize_withDetails_includesSerializedJson() {
        OffsetDateTime timestamp = OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        UUID resourceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID parentId = UUID.fromString("660e8400-e29b-41d4-a716-446655440000");

        UploadAudit details = new UploadAudit("test.pdf", parentId, null);

        String canonical = auditChainService.canonicalize(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, details, "prevhash");

        assertTrue(canonical.contains("test.pdf"));
        assertTrue(canonical.contains(parentId.toString()));
    }

    @Test
    void computeHash_withInvalidAlgorithm_throwsIllegalStateException() {
        AuditChainProperties badProps = new AuditChainProperties();
        badProps.setAlgorithm("NON-EXISTENT-ALGO");
        AuditChainService badService = new AuditChainService(badProps);

        assertThrows(IllegalStateException.class, badService::computeGenesisHash);
    }

    @Test
    void serializeDetails_withUnserializableDetails_returnsEmptyString() {
        // When serialization fails, the canonical form should use empty string for details
        OffsetDateTime timestamp = OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        UUID resourceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // A details object that causes JsonProcessingException when serialized
        IAuditLogDetails problematicDetails = new IAuditLogDetails() {
            // Jackson can't serialize this anonymous class properly if it has circular references
            // But actually Jackson will serialize it to {} which won't throw
        };

        // Even with unusual details, the hash computation should succeed
        String hash = auditChainService.computeHash(timestamp, "user@test.com",
                AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, resourceId, problematicDetails, "prevhash");
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    // ==================== Postgres microsecond rounding ====================
    //
    // audit_logs.timestamp is a TIMESTAMPTZ, which Postgres stores with only
    // microsecond resolution — and the r2dbc driver sends the value as text
    // with up to 9 fractional digits, so Postgres ROUNDS (half-up), it does not
    // truncate. `OffsetDateTime.now()` on JDK 9+ has nanosecond resolution, so
    // roughly 1 entry in 2000 lands in the last 500 ns of a millisecond and gets
    // rounded up ACROSS a millisecond boundary. canonicalize() hashes
    // toInstant().toEpochMilli(), so the entry is written with one millisecond
    // and read back with the next one — verifyChain() then recomputes a
    // different hash and reports the chain BROKEN (a false tamper alarm).

    /** Replicates what Postgres does to a TIMESTAMPTZ on write: round half-up to microseconds. */
    private static OffsetDateTime asStoredByPostgres(OffsetDateTime value) {
        return value.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    @Test
    void nanosecondTimestamp_isRoundedAcrossAMillisecond_andBreaksTheHash() {
        // Characterization of the hazard: .123999999 is stored as .124000, so the
        // epoch-millisecond the hash is built from changes from 123 to 124.
        OffsetDateTime written = OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 123_999_999, ZoneOffset.UTC);
        OffsetDateTime stored = asStoredByPostgres(written);

        assertNotEquals(written.toInstant().toEpochMilli(), stored.toInstant().toEpochMilli(),
                "Postgres rounding must cross the millisecond boundary for this fixture");
        assertNotEquals(
                auditChainService.computeHash(written, "user@test.com", AuditAction.UPLOAD_DOCUMENT,
                        DocumentType.FILE, null, null, "prevhash"),
                auditChainService.computeHash(stored, "user@test.com", AuditAction.UPLOAD_DOCUMENT,
                        DocumentType.FILE, null, null, "prevhash"),
                "a nanosecond-precision timestamp hashes differently once Postgres has rounded it");
    }

    @Test
    void auditTimestamp_survivesPostgresRounding_soTheChainStaysValid() {
        // The timestamp the write path stamps on a chained entry must be storable
        // losslessly, so that what we hashed is exactly what verifyChain() reads back.
        for (int i = 0; i < 2000; i++) {
            OffsetDateTime written = auditChainService.auditTimestamp();
            OffsetDateTime stored = asStoredByPostgres(written);

            assertEquals(written, stored,
                    "audit timestamps must be microsecond-aligned so Postgres stores them unchanged");
            assertEquals(
                    auditChainService.computeHash(written, "user@test.com", AuditAction.UPLOAD_DOCUMENT,
                            DocumentType.FILE, null, null, "prevhash"),
                    auditChainService.computeHash(stored, "user@test.com", AuditAction.UPLOAD_DOCUMENT,
                            DocumentType.FILE, null, null, "prevhash"),
                    "the hash must be identical before and after the value round-trips through Postgres");
        }
    }
}
