package org.openfilz.dms.service.signature.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SignatureEnvelope;
import reactor.test.StepVerifier;

import java.util.Optional;
import java.util.UUID;

class DefaultSignatureAccessPolicyTest {

    private final DefaultSignatureAccessPolicy policy = new DefaultSignatureAccessPolicy();

    @Test
    void canInitiate_activeDocument_true() {
        StepVerifier.create(policy.canInitiate(Document.builder().id(UUID.randomUUID()).active(true).build(), "a@x.io"))
                .expectNext(true).verifyComplete();
    }

    @Test
    void canInitiate_inactiveOrNullOrMissingDocument_false() {
        StepVerifier.create(policy.canInitiate(Document.builder().active(false).build(), "a@x.io"))
                .expectNext(false).verifyComplete();
        StepVerifier.create(policy.canInitiate(Document.builder().active(null).build(), "a@x.io"))
                .expectNext(false).verifyComplete();
        StepVerifier.create(policy.canInitiate(null, "a@x.io"))
                .expectNext(false).verifyComplete();
    }

    @Test
    void canManage_defaultIsInitiatorOnly_caseInsensitive() {
        SignatureEnvelope env = SignatureEnvelope.builder().initiatorEmail("Owner@X.io").build();
        StepVerifier.create(policy.canManage(env, "owner@x.io")).expectNext(true).verifyComplete();
        StepVerifier.create(policy.canManage(env, "other@x.io")).expectNext(false).verifyComplete();
        StepVerifier.create(policy.canManage(SignatureEnvelope.builder().build(), "owner@x.io"))
                .expectNext(false).verifyComplete();
    }

    @Test
    void resolveSignedDocumentParent_defaultsToSourceParent() {
        UUID parent = UUID.randomUUID();
        StepVerifier.create(policy.resolveSignedDocumentParent(null, Document.builder().parentId(parent).build()))
                .expectNext(Optional.of(parent)).verifyComplete();
        StepVerifier.create(policy.resolveSignedDocumentParent(null, Document.builder().build()))
                .expectNext(Optional.empty()).verifyComplete();
    }

    @Test
    void afterSignedDocumentPersisted_isNoop() {
        StepVerifier.create(policy.afterSignedDocumentPersisted(null, null)).verifyComplete();
    }
}
