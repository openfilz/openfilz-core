package org.openfilz.dms.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AuditChainProperties;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
}
