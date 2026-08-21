package org.openfilz.dms.service.signature;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SignatureEnvelope;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * Per-document authorisation seam for e-Sign. The core has no per-document permissions, so
 * {@code DefaultSignatureAccessPolicy} only checks that the document exists; the enterprise
 * layer overrides with its owner / write-share model and the {@code doc_owner} table.
 */
public interface SignatureAccessPolicy {

    /** May {@code userEmail} send {@code document} for signature? */
    Mono<Boolean> canInitiate(Document document, String userEmail);

    /** May {@code userEmail} read / manage this envelope? Default: the initiator only. */
    default Mono<Boolean> canManage(SignatureEnvelope envelope, String userEmail) {
        return Mono.just(envelope.getInitiatorEmail() != null
                && envelope.getInitiatorEmail().equalsIgnoreCase(userEmail));
    }

    /**
     * Parent folder for the signed copy. {@code Optional.empty()} = root. Default: next to the
     * source document.
     */
    default Mono<Optional<UUID>> resolveSignedDocumentParent(SignatureEnvelope envelope, Document source) {
        return Mono.just(Optional.ofNullable(source.getParentId()));
    }

    /** Hook after the signed document row is inserted (EE: ownership row). */
    default Mono<Void> afterSignedDocumentPersisted(SignatureEnvelope envelope, Document signedDocument) {
        return Mono.empty();
    }
}
