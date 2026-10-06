package org.openfilz.dms.service.impl;

import io.r2dbc.postgresql.codec.Json;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.SignatureConfig;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.dto.signature.ApplySignatureRequest;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.DeclineSignatureRequest;
import org.openfilz.dms.dto.signature.PublicSignatureView;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureEventDTO;
import org.openfilz.dms.dto.signature.SignatureFieldDTO;
import org.openfilz.dms.dto.signature.SignatureFieldInput;
import org.openfilz.dms.dto.signature.SignatureFieldValue;
import org.openfilz.dms.dto.signature.SignatureRecipientDTO;
import org.openfilz.dms.dto.signature.SignatureRecipientInput;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureEvent;
import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.enums.SignatureEventType;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.enums.SignatureRecipientStatus;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.repository.SignatureEnvelopeRepository;
import org.openfilz.dms.repository.SignatureEventRepository;
import org.openfilz.dms.repository.SignatureFieldRepository;
import org.openfilz.dms.repository.SignatureRecipientRepository;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.SignaturePdfService;
import org.openfilz.dms.service.SignatureService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.signature.SignatureAccessPolicy;
import org.openfilz.dms.service.signature.SignatureActorResolver;
import org.openfilz.dms.service.signature.SignatureCompletionListener;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.openfilz.dms.service.signature.SignatureNotifier;
import org.openfilz.dms.service.signature.SignatureOtpSender;
import org.openfilz.dms.service.signature.SignatureSealer;
import org.openfilz.dms.utils.SignatureJson;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core e-Sign lifecycle (Community Edition). Defence-in-depth identity re-checks, transactional
 * persistence, side-effects (mail / notify / audit attribution) fired only after commit.
 * Everything edition-specific goes through the {@code org.openfilz.dms.service.signature}
 * seams — see {@code openfilz-enterprise/docs/esign-ce-ee-split.md}.
 */
@Slf4j
@Service
public class SignatureServiceImpl implements SignatureService {

    static final UUID DEFAULT_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ().-]{6,32}$");

    static final String SIGN_NOT_AUTHORIZED_MESSAGE =
            "You are not allowed to send this document for signature.";

    private final SignatureEnvelopeRepository envelopeRepo;
    private final SignatureRecipientRepository recipientRepo;
    private final SignatureFieldRepository fieldRepo;
    private final SignatureEventRepository eventRepo;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final SignaturePdfService pdfService;
    private final AuditService auditService;
    private final TransactionalOperator tx;
    private final SignatureProperties props;
    private final CommonProperties commonProperties;
    private final SignatureAccessPolicy accessPolicy;
    private final SignatureActorResolver actorResolver;
    private final SignatureNotifier notifier;
    private final SignatureMailer mailer;
    private final SignatureSealer sealer;
    private final SignatureSealer coreSealer;
    private final SignatureCompletionListener completionListener;
    private final List<SignatureOtpSender> otpSenders;
    private final ObjectProvider<MetadataPostProcessor> metadataPostProcessorProvider;

    /** Envelopes with an in-flight finalization retry — guards {@link #healStuckEnvelope} against double page loads. */
    private final Set<UUID> healing = ConcurrentHashMap.newKeySet();

    public SignatureServiceImpl(SignatureEnvelopeRepository envelopeRepo,
                                SignatureRecipientRepository recipientRepo,
                                SignatureFieldRepository fieldRepo,
                                SignatureEventRepository eventRepo,
                                DocumentRepository documentRepository,
                                StorageService storageService,
                                SignaturePdfService pdfService,
                                AuditService auditService,
                                TransactionalOperator tx,
                                SignatureProperties props,
                                CommonProperties commonProperties,
                                SignatureAccessPolicy accessPolicy,
                                SignatureActorResolver actorResolver,
                                SignatureNotifier notifier,
                                SignatureMailer mailer,
                                SignatureSealer sealer,
                                @Qualifier(SignatureConfig.CORE_SEALER) SignatureSealer coreSealer,
                                SignatureCompletionListener completionListener,
                                List<SignatureOtpSender> otpSenders,
                                ObjectProvider<MetadataPostProcessor> metadataPostProcessorProvider) {
        this.envelopeRepo = envelopeRepo;
        this.recipientRepo = recipientRepo;
        this.fieldRepo = fieldRepo;
        this.eventRepo = eventRepo;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.pdfService = pdfService;
        this.auditService = auditService;
        this.tx = tx;
        this.props = props;
        this.commonProperties = commonProperties;
        this.accessPolicy = accessPolicy;
        this.actorResolver = actorResolver;
        this.notifier = notifier;
        this.mailer = mailer;
        this.sealer = sealer;
        this.coreSealer = coreSealer;
        this.completionListener = completionListener;
        this.otpSenders = otpSenders;
        this.metadataPostProcessorProvider = metadataPostProcessorProvider;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Initiator side
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Fair-use gate for deployments that hand e-Sign to people who have not paid for it — a
     * public demo, a trial tenant. Counts what this initiator created since the first of the
     * month and refuses beyond the configured ceiling. Disabled (0) by default, so a normal
     * self-hosted instance never pays for this query.
     */
    private Mono<Void> enforceEnvelopeQuota(String initiatorEmail) {
        int max = props.getQuota().getEnvelopesPerMonth();
        if (max <= 0) {
            return Mono.empty();
        }
        OffsetDateTime since = OffsetDateTime.now().withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
        return envelopeRepo.countByInitiatorSince(initiatorEmail, since)
                .filter(used -> used >= max)
                .flatMap(used -> Mono.<Void>error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Signature quota reached: this deployment allows " + max
                                + " envelope(s) per month and you have created " + used + " this month")));
    }

    @Override
    public Mono<SignatureEnvelopeDTO> create(CreateSignatureEnvelopeRequest req, Actor actor) {
        return enforceEnvelopeQuota(actor.email())
                .then(documentRepository.findByIdAndActive(req.sourceDocId(), true))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Source document not found")))
                .flatMap(doc -> accessPolicy.canInitiate(doc, actor.email())
                        .flatMap(ok -> Boolean.TRUE.equals(ok) ? Mono.just(doc)
                                : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, SIGN_NOT_AUTHORIZED_MESSAGE))))
                .flatMap(doc -> {
                    if (!isPdf(doc)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "Only PDF documents can be sent for signature"));
                    }
                    return readBytes(doc.getStoragePath()).flatMap(pdfBytes -> {
                        int pages;
                        try {
                            pages = pdfService.pageCount(pdfBytes);
                        } catch (IllegalArgumentException e) {
                            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "The document is not a readable PDF"));
                        }
                        validateRecipients(req, pages);

                        OffsetDateTime now = OffsetDateTime.now();
                        int days = req.expiresInDays() != null ? req.expiresInDays() : props.getDefaultExpiryDays();
                        UUID envelopeId = UUID.randomUUID();
                        boolean sendNow = req.shouldSend();
                        SignatureEnvelope env = SignatureEnvelope.builder()
                                .id(envelopeId).isNew(true)
                                .tenantId(DEFAULT_TENANT)
                                .initiatorId(actor.id())
                                .initiatorEmail(actor.email())
                                .title(req.title())
                                .message(req.message())
                                .sourceDocId(doc.getId())
                                .originalSha256(pdfService.sha256Hex(pdfBytes))
                                .status(sendNow ? SignatureEnvelopeStatus.SENT : SignatureEnvelopeStatus.DRAFT)
                                .sequential(req.isSequential())
                                .currentOrder(0)
                                .templateId(req.templateId())
                                .reminderDays(req.reminderDays())
                                .locale(normalizeLocale(req.locale()))
                                .createdAt(now).updatedAt(now)
                                .sentAt(sendNow ? now : null)
                                .expiresAt(now.plus(days, ChronoUnit.DAYS))
                                .build();

                        List<RecipientWithToken> recipients = new ArrayList<>();
                        List<SignatureField> fields = new ArrayList<>();
                        int sort = 0;
                        int position = 0;
                        for (SignatureRecipientInput in : req.recipients()) {
                            RecipientWithToken rt = buildRecipient(envelopeId, in, position++);
                            recipients.add(rt);
                            for (SignatureFieldInput fi : in.effectiveFields()) {
                                fields.add(buildField(envelopeId, rt.recipient().getId(), fi, sort++));
                            }
                        }
                        if (sendNow) {
                            env.setCurrentOrder(firstActionableOrder(recipients.stream().map(RecipientWithToken::recipient).toList()));
                        }

                        Mono<Void> persist = envelopeRepo.save(env)
                                .thenMany(Flux.fromIterable(recipients).concatMap(rt -> recipientRepo.save(rt.recipient())))
                                .thenMany(Flux.fromIterable(fields).concatMap(fieldRepo::save))
                                .then(event(envelopeId, SignatureEventType.ENVELOPE_CREATED, actor.email(),
                                        env.getOriginalSha256(), null, null))
                                .then(auditService.logAction(AuditAction.SIGNATURE_ENVELOPE_CREATED, DocumentType.FILE, doc.getId()));
                        if (sendNow) {
                            persist = persist
                                    .then(event(envelopeId, SignatureEventType.ENVELOPE_SENT, actor.email(),
                                            env.getOriginalSha256(), null, recipients.size() + " recipient(s)"))
                                    .then(auditService.logAction(AuditAction.SIGNATURE_ENVELOPE_SENT, DocumentType.FILE, doc.getId()));
                        }
                        return persist.as(tx::transactional)
                                .then(Mono.defer(() -> sendNow
                                        ? dispatchInvitations(env, doc.getName(), recipients, env.getCurrentOrder())
                                        : Mono.empty()))
                                .then(loadDto(env));
                    });
                });
    }

    @Override
    public Mono<SignatureEnvelopeDTO> send(UUID envelopeId, String initiatorEmail) {
        return loadOwned(envelopeId, initiatorEmail)
                .flatMap(env -> {
                    if (env.getStatus() != SignatureEnvelopeStatus.DRAFT) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Envelope is already " + env.getStatus()));
                    }
                    return recipientRepo.findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(envelopeId).collectList()
                            .zipWith(documentRepository.findByIdAndActive(env.getSourceDocId(), true)
                                    .map(Document::getName).defaultIfEmpty(env.getTitle()))
                            .flatMap(t -> {
                                List<SignatureRecipient> recipients = t.getT1();
                                OffsetDateTime now = OffsetDateTime.now();
                                env.setStatus(SignatureEnvelopeStatus.SENT);
                                env.setSentAt(now);
                                env.setUpdatedAt(now);
                                env.setCurrentOrder(firstActionableOrder(recipients));
                                // Re-issue every token so the invitation carries a fresh link.
                                List<RecipientWithToken> withTokens = recipients.stream().map(this::issueToken).toList();
                                return envelopeRepo.save(env)
                                        .thenMany(Flux.fromIterable(withTokens).concatMap(rt -> recipientRepo.save(rt.recipient())))
                                        .then(event(envelopeId, SignatureEventType.ENVELOPE_SENT, initiatorEmail,
                                                env.getOriginalSha256(), null, recipients.size() + " recipient(s)"))
                                        .then(auditService.logAction(AuditAction.SIGNATURE_ENVELOPE_SENT, DocumentType.FILE, env.getSourceDocId()))
                                        .as(tx::transactional)
                                        .then(Mono.defer(() -> dispatchInvitations(env, t.getT2(), withTokens, env.getCurrentOrder())))
                                        .thenReturn(env);
                            });
                })
                .flatMap(this::loadDto);
    }

    @Override
    public Flux<SignatureEnvelopeDTO> listSent(String initiatorEmail, SignatureEnvelopeStatus status) {
        String email = lower(initiatorEmail);
        Flux<SignatureEnvelope> flux = status == null
                ? envelopeRepo.findByInitiatorEmailOrderByCreatedAtDesc(email)
                : envelopeRepo.findByInitiatorEmailAndStatusOrderByCreatedAtDesc(email, status);
        return flux.collectList().flatMapMany(this::loadDtos);
    }

    @Override
    public Flux<SignatureEnvelopeDTO> listToSign(String userEmail) {
        return recipientRepo.findByRecipientEmailAndStatusInOrderByIdDesc(lower(userEmail),
                        List.of(SignatureRecipientStatus.PENDING, SignatureRecipientStatus.VIEWED))
                .filter(SignatureRecipient::isSigner)
                .map(SignatureRecipient::getEnvelopeId)
                .distinct()
                .collectList()
                .flatMapMany(ids -> ids.isEmpty() ? Flux.empty()
                        // one query for the envelopes, not one per recipient
                        : envelopeRepo.findAllById(ids)
                                .filter(env -> env.getStatus() == SignatureEnvelopeStatus.SENT)
                                .sort(Comparator.comparing(SignatureEnvelope::getCreatedAt).reversed())
                                .collectList()
                                .flatMapMany(this::loadDtos));
    }

    @Override
    public Mono<SignatureEnvelopeDTO> get(UUID envelopeId, String initiatorEmail) {
        return loadOwned(envelopeId, initiatorEmail).flatMap(this::loadDto);
    }

    @Override
    public Flux<SignatureEventDTO> events(UUID envelopeId, String initiatorEmail) {
        return loadOwned(envelopeId, initiatorEmail)
                .flatMapMany(env -> eventRepo.findByEnvelopeIdOrderByCreatedAtAsc(envelopeId))
                .map(SignatureEventDTO::from);
    }

    @Override
    public Mono<SignatureEnvelopeDTO> cancel(UUID envelopeId, String initiatorEmail) {
        return loadOwned(envelopeId, initiatorEmail)
                .flatMap(env -> {
                    if (env.getStatus().isTerminal()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Envelope is already " + env.getStatus()));
                    }
                    OffsetDateTime now = OffsetDateTime.now();
                    env.setStatus(SignatureEnvelopeStatus.CANCELLED);
                    env.setCancelledAt(now);
                    env.setUpdatedAt(now);
                    return envelopeRepo.save(env)
                            .then(recipientRepo.revokeTokens(envelopeId))
                            .then(event(envelopeId, SignatureEventType.ENVELOPE_CANCELLED, env.getInitiatorEmail(),
                                    env.getOriginalSha256(), null, null))
                            .then(auditService.logAction(AuditAction.SIGNATURE_ENVELOPE_CANCELLED, DocumentType.FILE, env.getSourceDocId()))
                            .thenReturn(env);
                })
                .as(tx::transactional)
                .flatMap(this::loadDto);
    }

    @Override
    public Mono<SignatureEnvelopeDTO> resend(UUID envelopeId, UUID recipientId, String initiatorEmail) {
        return loadOwned(envelopeId, initiatorEmail)
                .flatMap(env -> {
                    requireActive(env);
                    return recipientRepo.findById(recipientId)
                            .filter(r -> r.getEnvelopeId().equals(envelopeId))
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found")))
                            .flatMap(r -> {
                                if (!r.isActionable()) {
                                    return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                            "Recipient has already " + r.getStatus()));
                                }
                                RecipientWithToken rt = issueToken(r);
                                r.setReminderCount(r.getReminderCount() + 1);
                                r.setOtpVerifiedAt(null);   // a fresh link restarts the OTP step
                                env.setLastRemindedAt(OffsetDateTime.now());
                                env.setUpdatedAt(OffsetDateTime.now());
                                return recipientRepo.save(r)
                                        .then(envelopeRepo.save(env))
                                        .then(event(envelopeId, SignatureEventType.RECIPIENT_LINK_RESENT, initiatorEmail,
                                                null, null, r.getRecipientEmail()))
                                        .then(auditService.logAction(AuditAction.SIGNATURE_REMINDER_SENT, DocumentType.FILE, env.getSourceDocId()))
                                        .as(tx::transactional)
                                        .then(documentName(env))
                                        .doOnNext(name -> mailer.sendReminder(env, r, name, signLink(rt.rawToken())))
                                        .thenReturn(env);
                            });
                })
                .flatMap(this::loadDto);
    }

    @Override
    public Mono<SigningLink> rotateToken(UUID envelopeId, UUID recipientId, String initiatorEmail) {
        return loadOwned(envelopeId, initiatorEmail)
                .flatMap(env -> {
                    requireActive(env);
                    return recipientRepo.findById(recipientId)
                            .filter(r -> r.getEnvelopeId().equals(envelopeId))
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found")))
                            .flatMap(r -> {
                                if (!r.isActionable()) {
                                    return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                            "Recipient has already " + r.getStatus()));
                                }
                                String raw = issueToken(r).rawToken();
                                return recipientRepo.save(r).as(tx::transactional)
                                        .thenReturn(new SigningLink(r.getId(), raw, signLink(raw)));
                            });
                });
    }

    @Override
    public Mono<Resource> loadSignedDocument(UUID envelopeId, String initiatorEmail) {
        return loadOwned(envelopeId, initiatorEmail)
                .flatMap(env -> env.getSignedStoragePath() == null
                        ? Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Envelope is not completed"))
                        : storageService.loadFile(env.getSignedStoragePath()).cast(Resource.class));
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public (token) side
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public Mono<PublicSignatureView> getByToken(String rawToken) {
        return recipientByToken(rawToken).flatMap(this::publicView);
    }

    @Override
    public Mono<Resource> loadDocumentByToken(String rawToken) {
        return openRecipientByToken(rawToken)
                .flatMap(tr -> {
                    requireOtpSatisfied(tr.recipient());
                    return documentRepository.findByIdAndActive(tr.envelope().getSourceDocId(), true);
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found")))
                .flatMap(doc -> storageService.loadFile(doc.getStoragePath()).cast(Resource.class));
    }

    @Override
    public Mono<PublicSignatureView> recordView(String rawToken, String ip, String userAgent) {
        return recipientByToken(rawToken)
                .flatMap(r -> envelopeRepo.findById(r.getEnvelopeId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Envelope not found")))
                        .flatMap(env -> {
                            if (isClosedForSigner(env, r)) {
                                // A dead link records nothing — the view below only says the envelope is closed.
                                return Mono.just(r);
                            }
                            if (r.getStatus() == SignatureRecipientStatus.PENDING) {
                                r.setStatus(SignatureRecipientStatus.VIEWED);
                                r.setViewedAt(OffsetDateTime.now());
                                r.setSignerIp(ip);
                                r.setSignerUserAgent(truncate(userAgent, 512));
                                return recipientRepo.save(r)
                                        .then(event(r.getEnvelopeId(), SignatureEventType.RECIPIENT_VIEWED, r.getRecipientEmail(), null, ip, null))
                                        .as(tx::transactional)
                                        .thenReturn(r);
                            }
                            return healStuckEnvelope(r, env).thenReturn(r);
                        }))
                .flatMap(this::publicView);
    }

    /**
     * Recovery path for envelopes wedged by a historical finalization failure (seal provider
     * outage after the recipient row was already committed as SIGNED): every signer is SIGNED
     * but the envelope is still SENT and nothing can ever retry it. Detected when a signer
     * re-opens their link; errors are swallowed so the page still renders the current state.
     */
    private Mono<Void> healStuckEnvelope(SignatureRecipient r, SignatureEnvelope env) {
        if (r.getStatus() != SignatureRecipientStatus.SIGNED || env.getStatus() != SignatureEnvelopeStatus.SENT
                || !healing.add(env.getId())) {
            return Mono.empty();
        }
        return recipientRepo.findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(env.getId()).collectList()
                .filter(recipients -> recipients.stream().filter(SignatureRecipient::isSigner)
                        .allMatch(x -> x.getStatus() == SignatureRecipientStatus.SIGNED))
                .flatMap(recipients -> {
                    log.warn("[e-sign] envelope {} is fully signed but never completed — retrying finalization",
                            env.getId());
                    // Standalone finalization: its transaction commits first, then the audit row and the side effects.
                    return finalizeEnvelope(env, recipients)
                            .flatMap(cr -> auditCompleted(env, cr).then(completionSideEffects(env, recipients, cr)));
                })
                .onErrorResume(e -> {
                    log.error("[e-sign] finalization retry failed for envelope {}: {}", env.getId(), e.toString());
                    return Mono.empty();
                })
                .doFinally(sig -> healing.remove(env.getId()));
    }

    @Override
    public Mono<Void> requestOtp(String rawToken) {
        return openRecipientByToken(rawToken).flatMap(tr -> {
            SignatureRecipient r = tr.recipient();
            SignatureEnvelope env = tr.envelope();
            if (!r.requiresOtp()) {
                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "This recipient does not require a code"));
            }
            SignatureOtpSender sender = otpSenders.stream().filter(s -> s.supports(r.getAuthMethod())).findFirst()
                    .orElse(null);
            if (sender == null) {
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                        r.getAuthMethod() + " delivery is not available on this server"));
            }
            OffsetDateTime now = OffsetDateTime.now();
            long cooldownMs = otpRequestCooldownMillis();
            int maxRequests = props.getOtp().getMaxRequests() <= 0 ? Integer.MAX_VALUE : props.getOtp().getMaxRequests();
            // Fast path on the row we already hold; the UPDATE below re-checks both rules atomically.
            if (r.getOtpRequestCount() >= maxRequests) {
                return Mono.error(otpRequestsExhausted());
            }
            if (cooldownMs > 0 && r.getOtpRequestedAt() != null
                    && r.getOtpRequestedAt().plus(cooldownMs, ChronoUnit.MILLIS).isAfter(now)) {
                return Mono.error(otpCooldown(r.getOtpRequestedAt(), cooldownMs, now));
            }
            String code = newOtpCode(props.getOtp().getLength());
            OffsetDateTime expiresAt = now.plusMinutes(props.getOtp().getValidMinutes());
            OffsetDateTime notBefore = now.minus(cooldownMs, ChronoUnit.MILLIS);
            return recipientRepo.issueOtp(r.getId(), sha256(code), expiresAt, now, notBefore, maxRequests)
                    .defaultIfEmpty(0)
                    .flatMap(rows -> {
                        if (rows == 0) {
                            // Lost a race with a parallel request on the same link.
                            return Mono.error(r.getOtpRequestCount() + 1 >= maxRequests ? otpRequestsExhausted()
                                    : otpCooldown(now, cooldownMs, now));
                        }
                        r.setOtpHash(sha256(code));
                        r.setOtpExpiresAt(expiresAt);
                        r.setOtpAttempts(0);
                        r.setOtpVerifiedAt(null);
                        r.setOtpRequestedAt(now);
                        r.setOtpRequestCount(r.getOtpRequestCount() + 1);
                        return sender.send(env, r, code, props.getOtp().getValidMinutes());
                    });
        });
    }

    private long otpRequestCooldownMillis() {
        Duration cooldown = props.getOtp().getRequestCooldown();
        return cooldown == null || cooldown.isNegative() ? 0 : cooldown.toMillis();
    }

    private static ResponseStatusException otpRequestsExhausted() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many codes were requested for this link — ask the sender for a new link");
    }

    private static ResponseStatusException otpCooldown(OffsetDateTime lastRequest, long cooldownMs, OffsetDateTime now) {
        long waitSeconds = Math.max(1, Duration.between(now, lastRequest.plus(cooldownMs, ChronoUnit.MILLIS)).toSeconds());
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "A code was sent recently — wait " + waitSeconds + " s before requesting another one");
    }

    @Override
    public Mono<PublicSignatureView> verifyOtp(String rawToken, String code, String ip) {
        return openRecipientByToken(rawToken)
                .flatMap(tr -> {
                    SignatureRecipient r = tr.recipient();
                    SignatureEnvelope env = tr.envelope();
                    if (!r.requiresOtp()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "This recipient does not require a code"));
                    }
                    if (r.getOtpHash() == null || r.getOtpExpiresAt() == null || r.getOtpExpiresAt().isBefore(OffsetDateTime.now())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.GONE, "Code expired — request a new one"));
                    }
                    int max = props.getOtp().getMaxAttempts();
                    if (r.getOtpAttempts() >= max) {
                        return Mono.error(tooManyOtpAttempts());
                    }
                    // Reserve the attempt BEFORE comparing, with an atomic conditional increment that
                    // commits on its own: N parallel guesses get at most `max` slots between them, and
                    // a wrong guess keeps its slot consumed whatever happens next.
                    return recipientRepo.reserveOtpAttempt(r.getId(), max)
                            .defaultIfEmpty(0)
                            .flatMap(rows -> {
                                if (rows == 0) {
                                    return Mono.error(tooManyOtpAttempts());
                                }
                                r.setOtpAttempts(r.getOtpAttempts() + 1);
                                boolean ok = code != null && constantTimeEquals(sha256(code.trim()), r.getOtpHash());
                                if (!ok) {
                                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid code"));
                                }
                                r.setOtpVerifiedAt(OffsetDateTime.now());
                                r.setOtpHash(null);
                                return recipientRepo.save(r)
                                        .then(event(env.getId(), SignatureEventType.RECIPIENT_OTP_VERIFIED, r.getRecipientEmail(),
                                                null, ip, r.getAuthMethod().name()))
                                        .as(tx::transactional)
                                        .thenReturn(r);
                            });
                })
                .flatMap(r -> publicView(r, true));
    }

    private static ResponseStatusException tooManyOtpAttempts() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts — request a new code");
    }

    @Override
    public Mono<PublicSignatureView> applySignature(String rawToken, ApplySignatureRequest req, String ip, String userAgent) {
        return recipientByToken(rawToken)
                .flatMap(r -> envelopeRepo.findByIdForUpdate(r.getEnvelopeId())
                        // Row lock for the whole signing transaction: two last signers finishing together
                        // serialise, so the second one sees the first SIGNED row and completes the envelope
                        // instead of both leaving it SENT.
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Envelope not found")))
                        .flatMap(env -> {
                            requireOpenForSigner(env, r);
                            if (!r.isSigner()) {
                                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "CC recipients do not sign"));
                            }
                            if (r.getStatus() == SignatureRecipientStatus.SIGNED) {
                                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "You have already signed this document"));
                            }
                            if (r.getStatus() == SignatureRecipientStatus.DECLINED) {
                                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "This signing request was declined"));
                            }
                            if (env.isSequential() && r.getOrderIndex() != env.getCurrentOrder()) {
                                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "It is not your turn to sign yet"));
                            }
                            requireOtpSatisfied(r);
                            return fieldRepo.findByRecipientIdOrderBySortOrderAscIdAsc(r.getId()).collectList()
                                    .flatMap(fields -> {
                                        OffsetDateTime now = OffsetDateTime.now();
                                        applyValues(fields, req, now);
                                        r.setStatus(SignatureRecipientStatus.SIGNED);
                                        r.setSignedAt(now);
                                        r.setSignerIp(ip);
                                        r.setSignerUserAgent(truncate(userAgent, 512));
                                        // legacy mirror for old readers
                                        fields.stream().filter(f -> f.getType() == SignatureFieldType.SIGNATURE).findFirst().ifPresent(f -> {
                                            r.setSignatureImage(f.getValueImage());
                                            r.setSignatureTyped(f.getValueImage() == null ? f.getValue() : null);
                                        });
                                        return recipientRepo.save(r)
                                                .thenMany(Flux.fromIterable(fields).concatMap(fieldRepo::save))
                                                .then(event(env.getId(), SignatureEventType.RECIPIENT_SIGNED, r.getRecipientEmail(),
                                                        env.getOriginalSha256(), ip, fields.size() + " field(s)"))
                                                // Single transaction with advance/completion: if sealing (or any other
                                                // finalization step) fails, the SIGNED status rolls back too, so the
                                                // signer can retry — otherwise the envelope is stuck SENT forever with
                                                // an unretryable "already signed" recipient.
                                                .then(Mono.defer(() -> advanceOrComplete(env)))
                                                // Audit rows LAST: the chained audit insert takes the global audit
                                                // lock until commit, so it must come after the slow work (stamp, seal,
                                                // store) and right before the commit — never around it.
                                                .flatMap(transition -> auditSigned(r, env)
                                                        .then(transition.completion() == null ? Mono.empty()
                                                                : auditCompleted(env, transition.completion()))
                                                        .thenReturn(transition));
                                    });
                        })
                        .as(tx::transactional)
                        // Post-commit side effects: notifications, mails, thumbnail / indexing.
                        .flatMap(transition -> transition.afterCommit().thenReturn(r)))
                .flatMap(r -> publicView(r, true));
    }

    /** Attributes the signature audit row to the signer (identity from the validated token row). */
    private Mono<Void> auditSigned(SignatureRecipient r, SignatureEnvelope env) {
        return auditService.logAction(AuditAction.SIGNATURE_DOCUMENT_SIGNED, DocumentType.FILE, env.getSourceDocId())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(actorResolver.signerAuthentication(r)));
    }

    @Override
    public Mono<PublicSignatureView> decline(String rawToken, DeclineSignatureRequest req, String ip) {
        return openRecipientByToken(rawToken)
                .flatMap(tr -> {
                    SignatureRecipient r = tr.recipient();
                    SignatureEnvelope env = tr.envelope();
                    if (!r.isActionable()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Recipient has already " + r.getStatus()));
                    }
                    // Voiding the envelope is as consequential as signing it: same identity step.
                    requireOtpSatisfied(r);
                    OffsetDateTime now = OffsetDateTime.now();
                    r.setStatus(SignatureRecipientStatus.DECLINED);
                    r.setDeclineReason(truncate(req == null ? null : req.reason(), 1000));
                    r.setSignerIp(ip);
                    env.setStatus(SignatureEnvelopeStatus.DECLINED);
                    env.setUpdatedAt(now);
                    return recipientRepo.save(r)
                            .then(envelopeRepo.save(env))
                            .then(recipientRepo.revokeTokens(env.getId()))
                            .then(event(env.getId(), SignatureEventType.RECIPIENT_DECLINED, r.getRecipientEmail(),
                                    env.getOriginalSha256(), ip, r.getDeclineReason()))
                            .then(auditService.logAction(AuditAction.SIGNATURE_ENVELOPE_DECLINED, DocumentType.FILE, env.getSourceDocId())
                                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(actorResolver.signerAuthentication(r))))
                            .as(tx::transactional)
                            .then(notifier.declined(env, r).onErrorResume(e -> Mono.empty()))
                            .doOnSuccess(v -> mailer.sendDeclined(env, r))
                            .thenReturn(r);
                })
                .flatMap(r -> publicView(r, true));
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Sequential advance + completion
    // ═════════════════════════════════════════════════════════════════════

    /**
     * What a signature did to the envelope, decided inside the signing transaction:
     * {@code completion} is set when this signature completed the envelope; {@code afterCommit}
     * holds the side effects (invitations, notifications, mails, post-processing) the caller
     * runs once the transaction has committed — never inside it.
     */
    private record Transition(CompletionResult completion, Mono<Void> afterCommit) {
        static Transition none() {
            return new Transition(null, Mono.empty());
        }
    }

    private Mono<Transition> advanceOrComplete(SignatureEnvelope env) {
        return recipientRepo.findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(env.getId()).collectList()
                .flatMap(recipients -> {
                    boolean allSigned = recipients.stream().filter(SignatureRecipient::isSigner)
                            .allMatch(x -> x.getStatus() == SignatureRecipientStatus.SIGNED);
                    if (allSigned) {
                        return finalizeEnvelope(env, recipients)
                                .map(cr -> new Transition(cr, completionSideEffects(env, recipients, cr)));
                    }
                    if (!env.isSequential()) {
                        return Mono.just(Transition.none());
                    }
                    boolean currentDone = recipients.stream()
                            .filter(x -> x.isSigner() && x.getOrderIndex() == env.getCurrentOrder())
                            .allMatch(x -> x.getStatus() == SignatureRecipientStatus.SIGNED);
                    if (!currentDone) {
                        return Mono.just(Transition.none());
                    }
                    int next = recipients.stream().filter(SignatureRecipient::isActionable)
                            .mapToInt(SignatureRecipient::getOrderIndex).min().orElse(env.getCurrentOrder());
                    env.setCurrentOrder(next);
                    env.setUpdatedAt(OffsetDateTime.now());
                    // Mint fresh tokens for the newly unlocked signers and invite them.
                    List<RecipientWithToken> unlocked = recipients.stream()
                            .filter(x -> x.isActionable() && x.getOrderIndex() == next)
                            .map(this::issueToken)
                            .toList();
                    return envelopeRepo.save(env)
                            .thenMany(Flux.fromIterable(unlocked).concatMap(rt -> recipientRepo.save(rt.recipient())))
                            .then()
                            .as(tx::transactional)
                            .thenReturn(new Transition(null, Mono.defer(() -> documentName(env)
                                    .flatMap(name -> dispatchInvitations(env, name, unlocked, next)))));
                });
    }

    /**
     * Completes the envelope: stamp + seal + store the signed PDF, persist it as a document,
     * flip the envelope to COMPLETED, write the completion event, kill every signing link and
     * let the completion listener run — all in one transaction (joined when the caller has
     * one). No audit row and no side effect here: the caller adds the audit entries right
     * before its commit ({@link #auditCompleted}) and runs {@link #completionSideEffects}
     * after it.
     */
    private Mono<CompletionResult> finalizeEnvelope(SignatureEnvelope env, List<SignatureRecipient> recipients) {
        return documentRepository.findByIdAndActive(env.getSourceDocId(), true)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Source document not found")))
                .flatMap(srcDoc -> Mono.zip(
                                readBytes(srcDoc.getStoragePath()),
                                fieldRepo.findByEnvelopeIdOrderBySortOrderAscIdAsc(env.getId()).collectList(),
                                eventRepo.findByEnvelopeIdOrderByCreatedAtAsc(env.getId()).collectList())
                        .flatMap(t -> stampAndSeal(env, t.getT1(), recipients, t.getT2(), t.getT3()))
                        .flatMap(seal -> {
                            byte[] signedBytes = seal.bytes();
                            String storagePath = storageService.getUniqueStorageFileName(safeName(env.getTitle()) + "-signed.pdf");
                            return storageService.saveData(storagePath, toBuffers(signedBytes))
                                    .then(persistSignedDocument(env, srcDoc, storagePath, signedBytes.length))
                                    .flatMap(signedDoc -> {
                                        OffsetDateTime now = OffsetDateTime.now();
                                        env.setStatus(SignatureEnvelopeStatus.COMPLETED);
                                        env.setCompletedAt(now);
                                        env.setUpdatedAt(now);
                                        env.setSignedDocId(signedDoc.getId());
                                        env.setSignedStoragePath(storagePath);
                                        env.setSignedSha256(pdfService.sha256Hex(signedBytes));
                                        env.setSealProvider(seal.provider());
                                        return envelopeRepo.save(env)
                                                .then(event(env.getId(), SignatureEventType.ENVELOPE_COMPLETED, "system",
                                                        env.getSignedSha256(), null, "seal=" + seal.provider()
                                                                + (seal.flavor() != null ? " " + seal.flavor() : "")))
                                                // Terminal state: no signing link of this envelope may act again.
                                                .then(recipientRepo.revokeTokens(env.getId()))
                                                .then(completionListener.onCompleted(env, signedDoc, seal))
                                                .thenReturn(new CompletionResult(seal, signedDoc));
                                    });
                        }))
                .as(tx::transactional)
                .doOnSuccess(cr -> log.info("[e-sign] COMPLETED envelope={} signedDoc={} seal={}", env.getId(),
                        env.getSignedDocId(), cr == null ? "?" : cr.seal().provider()));
    }

    /** The completion audit row, attributed to the requester. Resolve the identity first — the lock is only taken by the insert. */
    private Mono<Void> auditCompleted(SignatureEnvelope env, CompletionResult cr) {
        return actorResolver.requesterAuthentication(env)
                .flatMap(auth -> auditService.logAction(AuditAction.SIGNATURE_ENVELOPE_COMPLETED, DocumentType.FILE,
                                cr.signedDoc().getId())
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)));
    }

    /** Post-commit only: in-app notification, the sealed PDF mailed to everyone, thumbnail / indexing. */
    private Mono<Void> completionSideEffects(SignatureEnvelope env, List<SignatureRecipient> recipients, CompletionResult cr) {
        return Mono.defer(() -> notifier.completed(env).onErrorResume(e -> Mono.empty())
                .then(Mono.fromRunnable(() -> {
                    emailSignedDocumentToAll(env, recipients, cr.seal().bytes());
                    triggerPostProcessing(cr.signedDoc());
                })));
    }

    private record CompletionResult(SignatureSealer.SealResult seal, Document signedDoc) {}

    /** Stamp fields + certificate, then seal through the primary sealer; fall back to the core sealer on failure. */
    Mono<SignatureSealer.SealResult> stampAndSeal(SignatureEnvelope env, byte[] original, List<SignatureRecipient> recipients,
                                                  List<SignatureField> fields, List<SignatureEvent> events) {
        return Mono.fromCallable(() -> pdfService.buildStampedDocument(original, env, recipients, fields, events))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(stamped -> sealer.seal(stamped, env)
                        .onErrorResume(err -> {
                            if (sealer == coreSealer) {
                                return Mono.error(err);
                            }
                            log.warn("[e-sign] seal provider '{}' failed for envelope {} — falling back to '{}'. Cause: {}",
                                    sealer.id(), env.getId(), coreSealer.id(), err.toString());
                            return coreSealer.seal(stamped, env);
                        }));
    }

    private Mono<Document> persistSignedDocument(SignatureEnvelope env, Document src, String storagePath, long size) {
        // An empty answer from the policy must not swallow the completion (the caller's audit + commit depend on a value).
        return accessPolicy.resolveSignedDocumentParent(env, src).defaultIfEmpty(Optional.empty()).flatMap(parent -> {
            OffsetDateTime now = OffsetDateTime.now();
            // No explicit id: Document has no Persistable flag, so a non-null id would make R2DBC UPDATE.
            Document signed = Document.builder()
                    .name(safeName(env.getTitle()) + " (signed).pdf")
                    .type(DocumentType.FILE)
                    .contentType("application/pdf")
                    .size(size)
                    .parentId(parent.orElse(null))
                    .storagePath(storagePath)
                    .createdAt(now).updatedAt(now)
                    .createdBy(env.getInitiatorEmail())
                    .updatedBy(env.getInitiatorEmail())
                    .active(true)
                    // env.getId() is a UUID → the inlined JSON is injection-safe.
                    .metadata(Json.of("{\"_signed\":true,\"_readOnly\":true,\"_signedEnvelopeId\":\"" + env.getId() + "\"}"))
                    .build();
            return documentRepository.save(signed)
                    .flatMap(saved -> accessPolicy.afterSignedDocumentPersisted(env, saved).thenReturn(saved));
        });
    }

    private void emailSignedDocumentToAll(SignatureEnvelope env, List<SignatureRecipient> recipients, byte[] sealed) {
        String fileName = safeName(env.getTitle()) + " (signed).pdf";
        Set<String> sent = new HashSet<>();
        if (env.getInitiatorEmail() != null && sent.add(env.getInitiatorEmail().toLowerCase())) {
            mailer.sendCompleted(env, env.getInitiatorEmail(), null, env.getLocale(), sealed, fileName);
        }
        for (SignatureRecipient r : recipients) {
            if (r.getRecipientEmail() != null && sent.add(r.getRecipientEmail().toLowerCase())) {
                mailer.sendCompleted(env, r.getRecipientEmail(), r.getRecipientName(),
                        r.getLocale() != null ? r.getLocale() : env.getLocale(), sealed, fileName);
            }
        }
    }

    private void triggerPostProcessing(Document signedDoc) {
        MetadataPostProcessor postProcessor = metadataPostProcessorProvider.getIfAvailable();
        if (postProcessor == null) return;
        try {
            postProcessor.processDocument(signedDoc);
        } catch (Exception e) {
            log.warn("[e-sign] post-processing for signed document {} failed: {}", signedDoc.getId(), e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Schedulers
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public Mono<Integer> sweepExpired() {
        return envelopeRepo.findSentPastDeadline(OffsetDateTime.now())
                .concatMap(env -> {
                    env.setStatus(SignatureEnvelopeStatus.EXPIRED);
                    env.setUpdatedAt(OffsetDateTime.now());
                    return envelopeRepo.save(env)
                            .then(recipientRepo.revokeTokens(env.getId()))
                            .then(event(env.getId(), SignatureEventType.ENVELOPE_EXPIRED, "system", env.getOriginalSha256(), null, null))
                            .then(actorResolver.requesterAuthentication(env)
                                    .flatMap(auth -> auditService.logAction(AuditAction.SIGNATURE_ENVELOPE_EXPIRED, DocumentType.FILE,
                                                    env.getSourceDocId())
                                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))))
                            .as(tx::transactional)
                            .thenReturn(1);
                })
                .reduce(0, Integer::sum);
    }

    @Override
    public Mono<Integer> sendDueReminders() {
        return envelopeRepo.findDueForReminder(OffsetDateTime.now())
                .concatMap(env -> recipientRepo.findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(env.getId()).collectList()
                        .flatMap(recipients -> {
                            List<RecipientWithToken> due = recipients.stream()
                                    .filter(r -> r.isActionable() && (!env.isSequential() || r.getOrderIndex() == env.getCurrentOrder()))
                                    .map(r -> {
                                        RecipientWithToken rt = issueToken(r);
                                        r.setReminderCount(r.getReminderCount() + 1);
                                        r.setOtpVerifiedAt(null);
                                        return rt;
                                    }).toList();
                            env.setLastRemindedAt(OffsetDateTime.now());
                            env.setUpdatedAt(OffsetDateTime.now());
                            return envelopeRepo.save(env)
                                    .thenMany(Flux.fromIterable(due).concatMap(rt -> recipientRepo.save(rt.recipient())))
                                    .thenMany(Flux.fromIterable(due).concatMap(rt -> event(env.getId(),
                                            SignatureEventType.RECIPIENT_REMINDED, "system", null, null, rt.recipient().getRecipientEmail())))
                                    .then(due.isEmpty() ? Mono.empty() : actorResolver.requesterAuthentication(env)
                                            .flatMap(auth -> auditService.logAction(AuditAction.SIGNATURE_REMINDER_SENT, DocumentType.FILE,
                                                            env.getSourceDocId())
                                                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))))
                                    .as(tx::transactional)
                                    .then(documentName(env))
                                    .doOnNext(name -> due.forEach(rt -> mailer.sendReminder(env, rt.recipient(), name, signLink(rt.rawToken()))))
                                    .thenReturn(due.size());
                        }))
                .reduce(0, Integer::sum);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════════════

    private record RecipientWithToken(SignatureRecipient recipient, String rawToken) {}

    /**
     * Re-issues the signing link of a recipient: new token (the previous link stops resolving),
     * revocation lifted, and the per-link OTP request throttle starts over. Every writer that
     * rotates a token goes through here.
     */
    private RecipientWithToken issueToken(SignatureRecipient r) {
        String raw = newRawToken();
        r.setTokenHash(sha256(raw));
        r.setTokenRevoked(false);
        r.setOtpRequestedAt(null);
        r.setOtpRequestCount(0);
        return new RecipientWithToken(r, raw);
    }

    private void validateRecipients(CreateSignatureEnvelopeRequest req, int pages) {
        boolean anySigner = false;
        Set<String> seen = new HashSet<>();
        for (SignatureRecipientInput in : req.recipients()) {
            if (!seen.add(in.email().toLowerCase())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Duplicate recipient " + in.email());
            }
            List<SignatureFieldInput> fields = in.effectiveFields();
            if (in.effectiveRole() == SignatureRecipientRole.CC) {
                if (!fields.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CC recipients cannot have fields");
                }
                continue;
            }
            anySigner = true;
            boolean hasSignature = fields.stream().anyMatch(f -> f.type() == SignatureFieldType.SIGNATURE
                    || f.type() == SignatureFieldType.INITIALS);
            if (!hasSignature) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Signer " + in.email() + " needs at least one SIGNATURE or INITIALS field");
            }
            SignatureAuthMethod auth = in.effectiveAuthMethod();
            if (auth == SignatureAuthMethod.SMS_OTP
                    && (in.phone() == null || !PHONE.matcher(in.phone()).matches())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Signer " + in.email() + " requires a valid phone number for SMS_OTP");
            }
            // Refuse a channel this deployment cannot deliver, rather than creating an envelope
            // whose recipient could never pass the OTP step (the request would 501 forever).
            if (auth != SignatureAuthMethod.NONE
                    && otpSenders.stream().noneMatch(sender -> sender.supports(auth))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        auth + " delivery is not available on this server — see openfilz.signature");
            }
            for (SignatureFieldInput f : fields) {
                if (f.page() >= pages) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Field page " + f.page() + " is out of range (document has " + pages + " page(s))");
                }
                if (f.x() < 0 || f.y() < 0 || f.w() <= 0 || f.h() <= 0 || f.x() + f.w() > 1.0001 || f.y() + f.h() > 1.0001) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field placement must stay within the page (0..1)");
                }
                if ((f.type() == SignatureFieldType.RADIO || f.type() == SignatureFieldType.SELECT)
                        && choices(f.options()).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, f.type() + " fields need options.choices");
                }
            }
        }
        if (!anySigner) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "At least one SIGNER recipient is required");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> choices(Map<String, Object> options) {
        if (options == null) return List.of();
        Object c = options.get("choices");
        if (c instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** Validate + write the submitted values into the recipient's fields. */
    private void applyValues(List<SignatureField> fields, ApplySignatureRequest req, OffsetDateTime now) {
        Map<UUID, SignatureFieldValue> byId = new HashMap<>();
        if (req.isLegacy()) {
            boolean hasImage = req.signatureImage() != null && !req.signatureImage().isBlank();
            boolean hasTyped = req.typedName() != null && !req.typedName().isBlank();
            if (hasImage == hasTyped) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide exactly one of signatureImage or typedName, or a fields array");
            }
            for (SignatureField f : fields) {
                if (f.getType() == SignatureFieldType.SIGNATURE || f.getType() == SignatureFieldType.INITIALS) {
                    byId.put(f.getId(), new SignatureFieldValue(f.getId(), hasTyped ? req.typedName() : null,
                            hasImage ? req.signatureImage() : null));
                }
            }
        } else {
            for (SignatureFieldValue v : req.fields()) byId.put(v.fieldId(), v);
        }
        Set<UUID> known = fields.stream().map(SignatureField::getId).collect(Collectors.toSet());
        for (UUID id : byId.keySet()) {
            if (!known.contains(id)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown field " + id);
            }
        }
        for (SignatureField f : fields) {
            SignatureFieldType type = f.getType();
            if (type.isAuto()) {
                f.setValue(now.toLocalDate().toString());
                f.setFilledAt(now);
                continue;
            }
            SignatureFieldValue v = byId.get(f.getId());
            String value = v == null || v.value() == null || v.value().isBlank() ? null : v.value().trim();
            String image = v == null || v.valueImage() == null || v.valueImage().isBlank() ? null : v.valueImage();
            if (image != null) {
                if (!image.startsWith("data:image/") && !image.matches("^[A-Za-z0-9+/=\\r\\n]+$")) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field " + label(f) + ": invalid image");
                }
                if (image.length() > props.getMaxImageBytes() * 4L / 3L + 64) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field " + label(f) + ": image too large");
                }
            }
            boolean provided = type.isImage() ? (image != null || (value != null && type != SignatureFieldType.IMAGE
                    && type != SignatureFieldType.STAMP)) : value != null;
            if (!provided) {
                if (f.isRequired()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field " + label(f) + " is required");
                }
                if (type == SignatureFieldType.CHECKBOX) {
                    f.setValue("false");
                    f.setFilledAt(now);
                }
                continue;
            }
            switch (type) {
                case NUMBER -> {
                    try {
                        Double.parseDouble(value.replace(',', '.'));
                    } catch (NumberFormatException e) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field " + label(f) + ": not a number");
                    }
                }
                case EMAIL -> {
                    if (!EMAIL.matcher(value).matches()) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field " + label(f) + ": invalid email");
                    }
                }
                case PHONE -> {
                    if (!PHONE.matcher(value).matches()) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field " + label(f) + ": invalid phone");
                    }
                }
                case CHECKBOX -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field " + label(f) + ": expected true/false");
                    }
                    value = value.toLowerCase();
                    if (f.isRequired() && !"true".equals(value)) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field " + label(f) + " must be checked");
                    }
                }
                case RADIO, SELECT -> {
                    List<String> choices = choices(SignatureJson.toMap(f.getOptions()));
                    if (!choices.isEmpty() && !choices.contains(value)) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field " + label(f) + ": not an allowed choice");
                    }
                }
                default -> { }
            }
            if (type.isImage()) {
                f.setValueImage(image);
                f.setValue(image == null ? value : null);
            } else {
                f.setValue(value);
            }
            f.setFilledAt(now);
        }
    }

    private static String label(SignatureField f) {
        return f.getLabel() != null && !f.getLabel().isBlank() ? "'" + f.getLabel() + "'" : f.getType().name();
    }

    private Mono<Void> dispatchInvitations(SignatureEnvelope env, String docName, List<RecipientWithToken> recipients, int order) {
        return Flux.fromIterable(recipients)
                .filter(rt -> {
                    SignatureRecipient r = rt.recipient();
                    if (!r.isSigner()) return false;
                    return !env.isSequential() || r.getOrderIndex() == order;
                })
                .concatMap(rt -> notifier.requested(env, rt.recipient()).onErrorResume(e -> Mono.empty())
                        .then(Mono.fromRunnable(() -> mailer.sendRequest(env, rt.recipient(), docName, signLink(rt.rawToken())))))
                .then()
                .doOnSuccess(v -> log.info("[e-sign] SENT envelope={} initiator={} recipients={} sequential={} order={}",
                        env.getId(), env.getInitiatorEmail(), recipients.size(), env.isSequential(), order));
    }

    private static int firstActionableOrder(List<SignatureRecipient> recipients) {
        return recipients.stream().filter(SignatureRecipient::isActionable)
                .mapToInt(SignatureRecipient::getOrderIndex).min().orElse(0);
    }

    private RecipientWithToken buildRecipient(UUID envelopeId, SignatureRecipientInput in, int position) {
        String rawToken = newRawToken();
        SignatureRecipient r = SignatureRecipient.builder()
                .id(UUID.randomUUID()).isNew(true)
                .envelopeId(envelopeId)
                .userId(in.userId())
                .recipientName(in.name())
                .recipientEmail(in.email().toLowerCase())
                .orderIndex(in.effectiveOrderIndex())
                .role(in.effectiveRole())
                .authMethod(in.effectiveAuthMethod())
                .phone(in.phone())
                .locale(normalizeLocale(in.locale()))
                .sortOrder(position)
                .status(SignatureRecipientStatus.PENDING)
                .tokenHash(sha256(rawToken))
                .build();
        // legacy mirror: first SIGNATURE placement
        in.effectiveFields().stream().filter(f -> f.type() == SignatureFieldType.SIGNATURE).findFirst().ifPresent(f -> {
            r.setFieldPage(f.page());
            r.setFieldX(f.x());
            r.setFieldY(f.y());
            r.setFieldW(f.w());
            r.setFieldH(f.h());
        });
        return new RecipientWithToken(r, rawToken);
    }

    private static SignatureField buildField(UUID envelopeId, UUID recipientId, SignatureFieldInput in, int sort) {
        return SignatureField.builder()
                .id(UUID.randomUUID()).isNew(true)
                .envelopeId(envelopeId)
                .recipientId(recipientId)
                .type(in.type())
                .page(in.page()).x(in.x()).y(in.y()).w(in.w()).h(in.h())
                .required(in.isRequired())
                .label(in.label())
                .options(SignatureJson.toJson(in.options()))
                .sortOrder(sort)
                .build();
    }

    /**
     * Resolves a raw signing token to its recipient row, revoked or not: the token is the only
     * authenticator, and a revoked one still identifies a party who may be told the envelope is
     * closed ({@link #publicView}). Every action goes through {@link #openRecipientByToken}.
     */
    private Mono<SignatureRecipient> recipientByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing token"));
        }
        return recipientRepo.findByTokenHash(sha256(rawToken))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid or expired signing link")));
    }

    private record TokenResolution(SignatureRecipient recipient, SignatureEnvelope envelope) {}

    /**
     * Token + envelope for an <em>action</em> (document, OTP, sign, decline): refuses revoked
     * links and terminal / expired envelopes with {@code 410 Gone}, drafts with {@code 409}.
     */
    private Mono<TokenResolution> openRecipientByToken(String rawToken) {
        return recipientByToken(rawToken)
                .flatMap(r -> envelopeRepo.findById(r.getEnvelopeId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Envelope not found")))
                        .map(env -> {
                            requireOpenForSigner(env, r);
                            return new TokenResolution(r, env);
                        }));
    }

    private Mono<SignatureEnvelope> loadOwned(UUID envelopeId, String userEmail) {
        return envelopeRepo.findById(envelopeId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Envelope not found")))
                .flatMap(env -> accessPolicy.canManage(env, userEmail)
                        .flatMap(ok -> Boolean.TRUE.equals(ok) ? Mono.just(env)
                                : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your envelope"))));
    }

    /**
     * The DTOs of a whole page, in two statements instead of two <em>per envelope</em>.
     * <p>
     * {@code loadDto} is right for one envelope and wrong for a list: the signatures page listed
     * every envelope, then asked for that envelope's fields and recipients one envelope at a time,
     * so a user with a dozen envelopes cost twenty-five round trips. Here the fields and the
     * recipients of the whole page are read once each and grouped in memory; the input order —
     * newest first — is preserved.
     */
    private Flux<SignatureEnvelopeDTO> loadDtos(List<SignatureEnvelope> envelopes) {
        if (envelopes.isEmpty()) {
            return Flux.empty();
        }
        if (envelopes.size() == 1) {
            return loadDto(envelopes.getFirst()).flux();
        }
        List<UUID> ids = envelopes.stream().map(SignatureEnvelope::getId).toList();
        return Mono.zip(fieldRepo.findByEnvelopeIdInOrderBySortOrderAscIdAsc(ids).collectList(),
                        recipientRepo.findByEnvelopeIdInOrderByOrderIndexAscSortOrderAscIdAsc(ids).collectList())
                .flatMapMany(t -> {
                    Map<UUID, List<SignatureFieldDTO>> fieldsByRecipient = t.getT1().stream()
                            .collect(Collectors.groupingBy(SignatureField::getRecipientId,
                                    Collectors.mapping(f -> SignatureFieldDTO.from(f, false), Collectors.toList())));
                    Map<UUID, List<SignatureRecipientDTO>> recipientsByEnvelope = t.getT2().stream()
                            .collect(Collectors.groupingBy(SignatureRecipient::getEnvelopeId, LinkedHashMap::new,
                                    Collectors.mapping(r -> SignatureRecipientDTO.from(r,
                                            fieldsByRecipient.getOrDefault(r.getId(), List.of())), Collectors.toList())));
                    return Flux.fromIterable(envelopes)
                            .map(env -> SignatureEnvelopeDTO.from(env, recipientsByEnvelope.getOrDefault(env.getId(), List.of())));
                });
    }

    private Mono<SignatureEnvelopeDTO> loadDto(SignatureEnvelope env) {
        return fieldRepo.findByEnvelopeIdOrderBySortOrderAscIdAsc(env.getId()).collectList()
                .flatMap(fields -> {
                    Map<UUID, List<SignatureFieldDTO>> byRecipient = fields.stream()
                            .collect(Collectors.groupingBy(SignatureField::getRecipientId,
                                    Collectors.mapping(f -> SignatureFieldDTO.from(f, false), Collectors.toList())));
                    return recipientRepo.findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(env.getId())
                            .map(r -> SignatureRecipientDTO.from(r, byRecipient.getOrDefault(r.getId(), List.of())))
                            .collectList()
                            .map(list -> SignatureEnvelopeDTO.from(env, list));
                });
    }

    private Mono<PublicSignatureView> publicView(SignatureRecipient r) {
        return publicView(r, false);
    }

    /**
     * What the holder of this token may see.
     * <ul>
     *   <li>Closed envelope (terminal, past its expiry, or revoked link): status only — no
     *       document name, no message, no fields. The completed PDF reaches the parties through
     *       the completion e-mail and the initiator's API, never through a dead link.</li>
     *   <li>OTP recipient who has not passed (or whose verification has aged out of
     *       {@code otp.verified-for}): the same minimal view with {@code otpRequired=true}, which
     *       the signing page turns into the code step before it ever asks for the PDF.</li>
     *   <li>Otherwise the full view: the recipient's own fields with values, other recipients'
     *       filled fields as <em>placements</em> (position + fill status, never their values or
     *       images).</li>
     * </ul>
     *
     * @param ownAction {@code true} when answering the recipient's own sign / decline / verify
     *                  call: their own fields are returned even though the envelope may have just
     *                  reached a terminal state, so the response shows what they signed.
     */
    private Mono<PublicSignatureView> publicView(SignatureRecipient r, boolean ownAction) {
        return envelopeRepo.findById(r.getEnvelopeId())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Envelope not found")))
                .flatMap(env -> {
                    SignatureEnvelopeStatus status = effectiveStatus(env);
                    boolean myTurn = status == SignatureEnvelopeStatus.SENT
                            && (!env.isSequential() || r.getOrderIndex() == env.getCurrentOrder());
                    boolean closed = isClosedForSigner(env, r);
                    if ((closed && !ownAction) || !otpSatisfied(r)) {
                        return Mono.just(minimalView(env, r, status, myTurn));
                    }
                    return Mono.zip(
                                    documentName(env),
                                    fieldRepo.findByEnvelopeIdOrderBySortOrderAscIdAsc(env.getId()).collectList())
                            .map(t -> {
                                List<SignatureField> all = t.getT2();
                                List<SignatureFieldDTO> mine = all.stream().filter(f -> f.getRecipientId().equals(r.getId()))
                                        .map(f -> SignatureFieldDTO.from(f, true)).toList();
                                // Other signers' fields: where they are and that they are filled — never what they contain.
                                List<SignatureFieldDTO> others = all.stream()
                                        .filter(f -> !f.getRecipientId().equals(r.getId()) && f.isFilled())
                                        .map(SignatureFieldDTO::placement).toList();
                                SignatureField first = all.stream().filter(f -> f.getRecipientId().equals(r.getId())
                                        && f.getType() == SignatureFieldType.SIGNATURE).findFirst().orElse(null);
                                return new PublicSignatureView(env.getTitle(), env.getMessage(), env.getInitiatorEmail(),
                                        t.getT1(), r.getRecipientName(), r.getRecipientEmail(),
                                        status, r.getStatus(), myTurn,
                                        authMethod(r), r.requiresOtp(), otpSatisfied(r),
                                        mine, others,
                                        first == null ? r.getFieldPage() : first.getPage(),
                                        first == null ? r.getFieldX() : first.getX(),
                                        first == null ? r.getFieldY() : first.getY(),
                                        first == null ? r.getFieldW() : first.getW(),
                                        first == null ? r.getFieldH() : first.getH(),
                                        first == null ? r.getSignatureImage() : first.getValueImage(),
                                        first == null ? r.getSignatureTyped() : (first.getValueImage() == null ? first.getValue() : null));
                            });
                });
    }

    /** Status page only: who is asking whom, and where the envelope stands. Nothing about the document. */
    private PublicSignatureView minimalView(SignatureEnvelope env, SignatureRecipient r, SignatureEnvelopeStatus status,
                                            boolean myTurn) {
        return new PublicSignatureView(env.getTitle(), null, env.getInitiatorEmail(),
                null, r.getRecipientName(), r.getRecipientEmail(),
                status, r.getStatus(), myTurn,
                authMethod(r), r.requiresOtp(), otpSatisfied(r),
                List.of(), List.of(),
                null, null, null, null, null, null, null);
    }

    private static SignatureAuthMethod authMethod(SignatureRecipient r) {
        return r.getAuthMethod() == null ? SignatureAuthMethod.NONE : r.getAuthMethod();
    }

    /** The status a signer should be told: an envelope past its deadline is EXPIRED even before the sweeper flips the column. */
    private static SignatureEnvelopeStatus effectiveStatus(SignatureEnvelope env) {
        return env.getStatus() == SignatureEnvelopeStatus.SENT && isPastExpiry(env)
                ? SignatureEnvelopeStatus.EXPIRED : env.getStatus();
    }

    private static boolean isPastExpiry(SignatureEnvelope env) {
        return env.getExpiresAt() != null && env.getExpiresAt().isBefore(OffsetDateTime.now());
    }

    /** Nothing may happen on this link any more: revoked token, terminal envelope, or deadline passed. */
    static boolean isClosedForSigner(SignatureEnvelope env, SignatureRecipient r) {
        return r.isTokenRevoked() || env.getStatus() == null || env.getStatus().isTerminal() || isPastExpiry(env);
    }

    /**
     * The OTP step, when the recipient has one, was passed on this link and has not aged out of
     * {@code openfilz.signature.otp.verified-for} (0 = never ages out).
     */
    boolean otpSatisfied(SignatureRecipient r) {
        if (!r.requiresOtp()) return true;
        if (r.getOtpVerifiedAt() == null) return false;
        Duration window = props.getOtp().getVerifiedFor();
        if (window == null || window.isZero() || window.isNegative()) return true;
        return r.getOtpVerifiedAt().plus(window).isAfter(OffsetDateTime.now());
    }

    private void requireOtpSatisfied(SignatureRecipient r) {
        if (!otpSatisfied(r)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access code not verified");
        }
    }

    /** Guard for every signer-side action: {@code 410} once the link is dead, {@code 409} for a draft. */
    private static void requireOpenForSigner(SignatureEnvelope env, SignatureRecipient r) {
        if (isClosedForSigner(env, r)) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This signing link is no longer valid — envelope is " + effectiveStatus(env));
        }
        if (env.getStatus() != SignatureEnvelopeStatus.SENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Envelope is " + env.getStatus());
        }
    }

    private Mono<String> documentName(SignatureEnvelope env) {
        return documentRepository.findByIdAndActive(env.getSourceDocId(), true)
                .map(Document::getName).defaultIfEmpty(env.getTitle());
    }

    private Mono<Void> event(UUID envelopeId, SignatureEventType type, String actor, String sha, String ip, String details) {
        return eventRepo.save(SignatureEvent.builder()
                .id(UUID.randomUUID()).isNew(true)
                .envelopeId(envelopeId)
                .eventType(type)
                .actor(actor)
                .docSha256(sha)
                .signerIp(ip)
                .details(truncate(details, 2000))
                .createdAt(OffsetDateTime.now())
                .build()).then();
    }

    private Mono<byte[]> readBytes(String storagePath) {
        return storageService.loadFile(storagePath)
                .flatMap(res -> Mono.fromCallable(() -> res.getInputStream().readAllBytes())
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private static Flux<DataBuffer> toBuffers(byte[] data) {
        return Flux.just(new DefaultDataBufferFactory().wrap(data));
    }

    private static void requireActive(SignatureEnvelope env) {
        if (env.getStatus() == SignatureEnvelopeStatus.SENT) return;
        HttpStatus s = env.getStatus() == SignatureEnvelopeStatus.EXPIRED ? HttpStatus.GONE : HttpStatus.CONFLICT;
        throw new ResponseStatusException(s, "Envelope is " + env.getStatus());
    }

    private static boolean isPdf(Document doc) {
        return (doc.getContentType() != null && doc.getContentType().toLowerCase().contains("pdf"))
                || (doc.getName() != null && doc.getName().toLowerCase().endsWith(".pdf"));
    }

    String signLink(String rawToken) {
        String base = props.getWebBaseUrl() != null && !props.getWebBaseUrl().isBlank()
                ? props.getWebBaseUrl() : commonProperties.getWebPublicBaseUrl();
        if (base == null || base.isBlank()) base = "http://localhost:4200/";
        if (!base.endsWith("/")) base = base + "/";
        return base + "sign?token=" + rawToken;
    }

    static String newRawToken() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static String newOtpCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }

    private String sha256(String s) {
        return pdfService.sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) return null;
        return locale.trim().toLowerCase().split("[-_]")[0];
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    private static String safeName(String s) {
        return s == null ? "document" : s.replaceAll("[^a-zA-Z0-9-_ ]", "_").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
