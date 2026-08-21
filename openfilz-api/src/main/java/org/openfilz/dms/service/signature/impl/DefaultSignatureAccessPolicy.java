package org.openfilz.dms.service.signature.impl;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.signature.SignatureAccessPolicy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Community Edition policy: there are no per-document permissions in core, so any
 * CONTRIBUTOR may send any active document for signature. The enterprise layer replaces this
 * bean with its owner / write-share model ({@code @Primary}).
 */
@Service
public class DefaultSignatureAccessPolicy implements SignatureAccessPolicy {

    @Override
    public Mono<Boolean> canInitiate(Document document, String userEmail) {
        return Mono.just(document != null && Boolean.TRUE.equals(document.getActive()));
    }
}
