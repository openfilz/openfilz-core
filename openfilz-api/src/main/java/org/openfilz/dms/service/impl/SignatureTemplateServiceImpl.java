package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.InstantiateTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureFieldInput;
import org.openfilz.dms.dto.signature.SignatureRecipientInput;
import org.openfilz.dms.dto.signature.SignatureTemplateDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateField;
import org.openfilz.dms.dto.signature.SignatureTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureTemplateRole;
import org.openfilz.dms.entity.SignatureTemplate;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.SignatureTemplateRepository;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.SignatureService;
import org.openfilz.dms.service.SignatureTemplateService;
import org.openfilz.dms.utils.SignatureJson;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignatureTemplateServiceImpl implements SignatureTemplateService {

    private final SignatureTemplateRepository repo;
    private final SignatureService signatureService;
    private final AuditService auditService;

    @Override
    public Mono<SignatureTemplateDTO> create(SignatureTemplateRequest req, String ownerEmail) {
        validate(req);
        OffsetDateTime now = OffsetDateTime.now();
        SignatureTemplate t = SignatureTemplate.builder()
                .id(UUID.randomUUID()).isNew(true)
                .ownerEmail(ownerEmail.toLowerCase())
                .createdAt(now)
                .build();
        apply(t, req, now);
        return repo.save(t)
                .flatMap(saved -> (saved.getSourceDocId() == null ? Mono.<Void>empty()
                        : auditService.logAction(AuditAction.SIGNATURE_TEMPLATE_CREATED, DocumentType.FILE, saved.getSourceDocId()))
                        .thenReturn(saved))
                .map(this::toDto);
    }

    @Override
    public Mono<SignatureTemplateDTO> update(UUID id, SignatureTemplateRequest req, String ownerEmail) {
        validate(req);
        return owned(id, ownerEmail)
                .flatMap(t -> {
                    apply(t, req, OffsetDateTime.now());
                    return repo.save(t);
                })
                .map(this::toDto);
    }

    @Override
    public Flux<SignatureTemplateDTO> list(String ownerEmail) {
        return repo.findByOwnerEmailOrderByUpdatedAtDesc(ownerEmail.toLowerCase()).map(this::toDto);
    }

    @Override
    public Mono<SignatureTemplateDTO> get(UUID id, String ownerEmail) {
        return owned(id, ownerEmail).map(this::toDto);
    }

    @Override
    public Mono<Void> delete(UUID id, String ownerEmail) {
        return owned(id, ownerEmail)
                .flatMap(t -> repo.delete(t)
                        .then(t.getSourceDocId() == null ? Mono.empty()
                                : auditService.logAction(AuditAction.SIGNATURE_TEMPLATE_DELETED, DocumentType.FILE, t.getSourceDocId())));
    }

    @Override
    public Mono<SignatureEnvelopeDTO> instantiate(UUID id, InstantiateTemplateRequest req, SignatureService.Actor actor) {
        return owned(id, actor.email()).flatMap(t -> {
            UUID sourceDocId = req.sourceDocId() != null ? req.sourceDocId() : t.getSourceDocId();
            if (sourceDocId == null) {
                return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "This template has no default document — provide sourceDocId"));
            }
            List<SignatureTemplateRole> roles = SignatureJson.toList(t.getRoles(), SignatureTemplateRole.class);
            List<SignatureTemplateField> fields = SignatureJson.toList(t.getFields(), SignatureTemplateField.class);
            Map<String, SignatureTemplateRole> roleByName = new HashMap<>();
            roles.forEach(r -> roleByName.put(r.name(), r));
            Map<String, List<SignatureFieldInput>> fieldsByRole = new HashMap<>();
            for (SignatureTemplateField f : fields) {
                fieldsByRole.computeIfAbsent(f.role(), k -> new ArrayList<>()).add(f.toInput());
            }
            Set<String> bound = new HashSet<>();
            List<SignatureRecipientInput> recipients = new ArrayList<>();
            for (InstantiateTemplateRequest.RoleBinding b : req.recipients()) {
                SignatureTemplateRole role = roleByName.get(b.role());
                if (role == null) {
                    return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Unknown template role '" + b.role() + "'"));
                }
                if (!bound.add(b.role())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Role '" + b.role() + "' is bound twice"));
                }
                recipients.add(new SignatureRecipientInput(b.userId(), b.name(), b.email(),
                        role.orderIndex() == null ? 0 : role.orderIndex(), role.role(), role.authMethod(),
                        b.phone(), b.locale(), fieldsByRole.getOrDefault(b.role(), List.of()), null));
            }
            for (String name : roleByName.keySet()) {
                if (!bound.contains(name)) {
                    return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Template role '" + name + "' is not bound to a recipient"));
                }
            }
            CreateSignatureEnvelopeRequest create = new CreateSignatureEnvelopeRequest(
                    sourceDocId,
                    req.title() != null && !req.title().isBlank() ? req.title() : t.getName(),
                    req.message() != null ? req.message() : t.getMessage(),
                    recipients,
                    req.expiresInDays() != null ? req.expiresInDays() : t.getExpiresInDays(),
                    t.isSequential(),
                    req.reminderDays(),
                    req.locale(),
                    req.send(),
                    t.getId());
            return signatureService.create(create, actor);
        });
    }

    // ─────────────────────────────────────────────────────────────────────

    private static void validate(SignatureTemplateRequest req) {
        Set<String> roleNames = new HashSet<>();
        for (SignatureTemplateRole r : req.roles()) {
            if (!roleNames.add(r.name())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Duplicate role '" + r.name() + "'");
            }
        }
        for (SignatureTemplateField f : req.fields()) {
            if (!roleNames.contains(f.role())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Field references unknown role '" + f.role() + "'");
            }
            if (f.x() < 0 || f.y() < 0 || f.w() <= 0 || f.h() <= 0 || f.x() + f.w() > 1.0001 || f.y() + f.h() > 1.0001) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field placement must stay within the page (0..1)");
            }
        }
    }

    private static void apply(SignatureTemplate t, SignatureTemplateRequest req, OffsetDateTime now) {
        t.setName(req.name());
        t.setDescription(req.description());
        t.setSourceDocId(req.sourceDocId());
        t.setRoles(SignatureJson.toJson(req.roles()));
        t.setFields(SignatureJson.toJson(req.fields()));
        t.setMessage(req.message());
        t.setExpiresInDays(req.expiresInDays());
        t.setSequential(Boolean.TRUE.equals(req.sequential()));
        t.setUpdatedAt(now);
    }

    private Mono<SignatureTemplate> owned(UUID id, String ownerEmail) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found")))
                .flatMap(t -> t.getOwnerEmail().equalsIgnoreCase(ownerEmail) ? Mono.just(t)
                        : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your template")));
    }

    private SignatureTemplateDTO toDto(SignatureTemplate t) {
        return new SignatureTemplateDTO(t.getId(), t.getOwnerEmail(), t.getName(), t.getDescription(), t.getSourceDocId(),
                SignatureJson.toList(t.getRoles(), SignatureTemplateRole.class),
                SignatureJson.toList(t.getFields(), SignatureTemplateField.class),
                t.getMessage(), t.getExpiresInDays(), t.isSequential(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
