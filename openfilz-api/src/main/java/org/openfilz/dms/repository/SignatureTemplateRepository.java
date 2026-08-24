package org.openfilz.dms.repository;

import org.openfilz.dms.entity.SignatureTemplate;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SignatureTemplateRepository extends ReactiveCrudRepository<SignatureTemplate, UUID> {
    Flux<SignatureTemplate> findByOwnerEmailOrderByUpdatedAtDesc(String ownerEmail);
}
