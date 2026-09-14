package org.openfilz.dms.service;

import org.openfilz.dms.dto.response.DocumentIntegrityRecord;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The append-only record of what a document's content hashed to, and when (C2).
 * <p>
 * The fingerprint also lives in {@code documents.metadata} under {@code sha256}, and still does —
 * existing clients read it there. But that is a JSONB field the metadata APIs are built to
 * rewrite, so it cannot be the reference: whoever could alter the bytes could alter the value
 * they are checked against. This ledger is the reference, protected by a database trigger that
 * refuses {@code UPDATE} and {@code DELETE} outright.
 * <p>
 * Entries are written wherever content is established — upload, content replacement, version
 * restore — and never afterwards. Reading them is what makes a verification campaign (C3)
 * meaningful, and what supplies the fingerprint quoted in an archive deposit record.
 */
public interface DocumentIntegrityService {

    /**
     * Append one entry. Never updates: the same document legitimately accumulates one row per
     * content change, and the sequence is the point.
     */
    Mono<Void> record(UUID documentId, String storagePath, String versionId, String hash);

    /** Every entry for a document, newest first. */
    Flux<DocumentIntegrityRecord> history(UUID documentId);

    /** The most recent entry, or empty when the document predates the ledger. */
    Mono<DocumentIntegrityRecord> latest(UUID documentId);
}
