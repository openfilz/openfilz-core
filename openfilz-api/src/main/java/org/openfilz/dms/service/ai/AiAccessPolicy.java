package org.openfilz.dms.service.ai;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Per-user document access policy consulted by the AI feature (tools, RAG retrieval).
 * <p>
 * The AI assistant must only ever surface or act on documents the requesting user is
 * allowed to see: the tools resolve documents by name via raw repository queries and read
 * content straight from storage, so they cannot rely on the endpoint-level authorization
 * alone. Every read/modify decision is routed through this policy with the requesting
 * user's email.
 * <p>
 * The core (single-tenant, role-gated) model has no per-document permissions, so the
 * default implementation permits everything ({@link PermitAllAiAccessPolicy}). Extension
 * layers with document-level sharing register a {@code @Primary} implementation backed by
 * their ownership/share model. Implementations must not depend on the reactive security
 * context — the tools invoke them from blocking tool threads where it is absent.
 */
public interface AiAccessPolicy {

    /**
     * True when this policy performs no per-document filtering (the core default).
     * Lets callers skip per-item checks and, e.g., use exact SQL counts.
     */
    default boolean permitAll() {
        return true;
    }

    /** Can the user see this document at all (existence, metadata, content)? */
    Mono<Boolean> canRead(UUID documentId, String userEmail);

    /** Can the user modify this document (rename/move it, or create content inside this folder)? */
    Mono<Boolean> canModify(UUID documentId, String userEmail);

    /** Can the user create content at the root level? */
    default Mono<Boolean> canCreateAtRoot(String userEmail) {
        return Mono.just(true);
    }
}
