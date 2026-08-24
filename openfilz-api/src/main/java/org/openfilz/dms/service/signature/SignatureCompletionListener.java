package org.openfilz.dms.service.signature;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SignatureEnvelope;
import reactor.core.publisher.Mono;

/**
 * Called inside the completion transaction once the signed document row exists. Enterprise
 * uses it to persist the veraPDF compliance report ({@code signature_archive}).
 */
public interface SignatureCompletionListener {

    default Mono<Void> onCompleted(SignatureEnvelope envelope, Document signedDocument,
                                   SignatureSealer.SealResult seal) {
        return Mono.empty();
    }
}
