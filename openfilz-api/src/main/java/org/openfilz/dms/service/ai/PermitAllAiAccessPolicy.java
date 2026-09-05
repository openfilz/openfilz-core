package org.openfilz.dms.service.ai;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Core default {@link AiAccessPolicy}: every authenticated user may see and act on every
 * document, matching the core authorization model (role-gated endpoints, no per-document
 * permissions). Extension layers with document-level sharing override this with a
 * {@code @Primary} bean.
 */
@Service
@Lazy
public class PermitAllAiAccessPolicy implements AiAccessPolicy {

    @Override
    public Mono<Boolean> canRead(UUID documentId, String userEmail) {
        return Mono.just(true);
    }

    @Override
    public Mono<Boolean> canModify(UUID documentId, String userEmail) {
        return Mono.just(true);
    }

    @Override
    public Mono<Set<UUID>> readable(Collection<UUID> documentIds, String userEmail) {
        return Mono.just(documentIds == null ? Set.of() : Set.copyOf(documentIds));
    }

    @Override
    public Mono<Set<UUID>> modifiable(Collection<UUID> documentIds, String userEmail) {
        return Mono.just(documentIds == null ? Set.of() : Set.copyOf(documentIds));
    }
}
