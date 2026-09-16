package org.openfilz.dms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry of a document's append-only integrity ledger (C2).
 *
 * @param documentId  the document the fingerprint was taken of
 * @param storagePath object key at the time it was recorded
 * @param versionId   storage-backend object version, {@code null} on backends without versioning
 * @param algorithm   digest algorithm, currently always {@code SHA-256}
 * @param hash        the fingerprint itself
 * @param recordedAt  when it was recorded — never updated afterwards
 * @param recordedBy  the principal whose action produced this content
 */
@Schema(description = "One entry of a document's append-only integrity ledger")
public record DocumentIntegrityRecord(
        UUID documentId,
        String storagePath,
        String versionId,
        String algorithm,
        String hash,
        OffsetDateTime recordedAt,
        String recordedBy) {
}
