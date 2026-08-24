package org.openfilz.dms.service.signature;

import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import reactor.core.publisher.Mono;

/** In-app notification seam. Core: no-op (no notification centre). Enterprise: {@code NotificationService}. */
public interface SignatureNotifier {

    default Mono<Void> requested(SignatureEnvelope envelope, SignatureRecipient recipient) {
        return Mono.empty();
    }

    default Mono<Void> completed(SignatureEnvelope envelope) {
        return Mono.empty();
    }

    default Mono<Void> declined(SignatureEnvelope envelope, SignatureRecipient decliner) {
        return Mono.empty();
    }
}
