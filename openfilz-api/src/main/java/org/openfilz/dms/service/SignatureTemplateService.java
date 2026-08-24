package org.openfilz.dms.service;

import org.openfilz.dms.dto.signature.InstantiateTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Reusable e-Sign templates: CRUD (owner-scoped) + instantiation into a real envelope. */
public interface SignatureTemplateService {

    Mono<SignatureTemplateDTO> create(SignatureTemplateRequest req, String ownerEmail);

    Mono<SignatureTemplateDTO> update(UUID id, SignatureTemplateRequest req, String ownerEmail);

    Flux<SignatureTemplateDTO> list(String ownerEmail);

    Mono<SignatureTemplateDTO> get(UUID id, String ownerEmail);

    Mono<Void> delete(UUID id, String ownerEmail);

    /** Build (and by default send) an envelope from the template with concrete recipients. */
    Mono<SignatureEnvelopeDTO> instantiate(UUID id, InstantiateTemplateRequest req, SignatureService.Actor actor);
}
