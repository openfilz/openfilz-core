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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
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
                                List<RecipientWithToken> withTokens = recipients.stream().map(r -> {
                                    String raw = newRawToken();
                                    r.setTokenHash(sha256(raw));
                                    r.setTokenRevoked(false);
                                    return new RecipientWithToken(r, raw);
                                }).toList();
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
        return flux.concatMap(this::loadDto);
    }

    @Override
    public Flux<SignatureEnvelopeDTO> listToSign(String userEmail) {
        return recipientRepo.findByRecipientEmailAndStatusInOrderByIdDesc(lower(userEmail),
                        List.of(SignatureRecipientStatus.PENDING, SignatureRecipientStatus.VIEWED))
                .filter(SignatureRecipient::isSigner)
                .concatMap(r -> envelopeRepo.findById(r.getEnvelopeId()))
                .filter(env -> env.getStatus() == SignatureEnvelopeStatus.SENT)
                .distinct(SignatureEnvelope::getId)
                .concatMap(this::loadDto);
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
                                String raw = newRawToken();
                                r.setTokenHash(sha256(raw));
                                r.setTokenRevoked(false);
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
                                        .doOnNext(name -> mailer.sendReminder(env, r, name, signLink(raw)))
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
                                String raw = newRawToken();
                                r.setTokenHash(sha256(raw));
                                r.setTokenRevoked(false);
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
        return recipientByToken(rawToken)
                .flatMap(r -> envelopeRepo.findById(r.getEnvelopeId()))
                .flatMap(env -> documentRepository.findByIdAndActive(env.getSourceDocId(), true))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found")))
                .flatMap(doc -> storageService.loadFile(doc.getStoragePath()).cast(Resource.class));
    }

    @Override
    public Mono<PublicSignatureView> recordView(String rawToken, String ip, String userAgent) {
        return recipientByToken(rawToken)
                .flatMap(r -> {
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
                    return healStuckEnvelope(r).thenReturn(r);
                })
                .flatMap(this::publicView);
    }

    /**
     * Recovery path for envelopes wedged by a historical finalization failure (seal provider
     * outage after the recipient row was already committed as SIGNED): every signer is SIGNED
     * but the envelope is still SENT and nothing can ever retry it. Detected when a signer
     * re-opens their link; errors are swallowed so the page still renders the current state.
     */
    private Mono<Void> healStuckEnvelope(SignatureRecipient r) {
        if (r.getStatus() != SignatureRecipientStatus.SIGNED) {
            return Mono.empty();
        }
        return envelopeRepo.findById(r.getEnvelopeId())
                .filter(env -> env.getStatus() == SignatureEnvelopeStatus.SENT)
                .filter(env -> healing.add(env.getId()))
                .flatMap(env -> recipientRepo.findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(env.getId()).collectList()
                        .filter(recipients -> recipients.stream().filter(SignatureRecipient::isSigner)
                                .allMatch(x -> x.getStatus() == SignatureRecipientStatus.SIGNED))
                        .flatMap(recipients -> {
                            log.warn("[e-sign] envelope {} is fully signed but never completed — retrying finalization",
                                    env.getId());
                            return finalizeEnvelope(env, recipients);
                        })
                        .onErrorResume(e -> {
                            log.error("[e-sign] finalization retry failed for envelope {}: {}", env.getId(), e.toString());
                            return Mono.empty();
                        })
                        .doFinally(sig -> healing.remove(env.getId())));
    }

    @Override
    public Mono<Void> requestOtp(String rawToken) {
        return recipientByToken(rawToken)
                .flatMap(r -> envelopeRepo.findById(r.getEnvelopeId()).flatMap(env -> {
                    requireActive(env);
                    if (!r.requiresOtp()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "This recipient does not require a code"));
                    }
                    SignatureOtpSender sender = otpSenders.stream().filter(s -> s.supports(r.getAuthMethod())).findFirst()
                            .orElse(null);
                    if (sender == null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                                r.getAuthMethod() + " delivery is not available on this server"));
                    }
                    String code = newOtpCode(props.getOtp().getLength());
                    r.setOtpHash(sha256(code));
                    r.setOtpExpiresAt(OffsetDateTime.now().plusMinutes(props.getOtp().getValidMinutes()));
                    r.setOtpAttempts(0);
                    r.setOtpVerifiedAt(null);
                    return recipientRepo.save(r).as(tx::transactional)
                            .then(sender.send(env, r, code, props.getOtp().getValidMinutes()));
                }));
    }

    @Override
    public Mono<PublicSignatureView> verifyOtp(String rawToken, String code, String ip) {
        return recipientByToken(rawToken)
                .flatMap(r -> envelopeRepo.findById(r.getEnvelopeId()).flatMap(env -> {
                    requireActive(env);
                    if (!r.requiresOtp()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "This recipient does not require a code"));
                    }
                    if (r.getOtpHash() == null || r.getOtpExpiresAt() == null || r.getOtpExpiresAt().isBefore(OffsetDateTime.now())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.GONE, "Code expired — request a new one"));
                    }
                    if (r.getOtpAttempts() >= props.getOtp().getMaxAttempts()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts — request a new code"));
                    }
                    boolean ok = code != null && constantTimeEquals(sha256(code.trim()), r.getOtpHash());
                    if (!ok) {
                        r.setOtpAttempts(r.getOtpAttempts() + 1);
                        return recipientRepo.save(r).as(tx::transactional)
                                .then(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid code")));
                    }
                    r.setOtpVerifiedAt(OffsetDateTime.now());
                    r.setOtpHash(null);
                    return recipientRepo.save(r)
                            .then(event(env.getId(), SignatureEventType.RECIPIENT_OTP_VERIFIED, r.getRecipientEmail(),
                                    null, ip, r.getAuthMethod().name()))
                            .as(tx::transactional)
                            .thenReturn(r);
                }))
                .flatMap(this::publicView);
    }

    @Override
    public Mono<PublicSignatureView> applySignature(String rawToken, ApplySignatureRequest req, String ip, String userAgent) {
        return recipientByToken(rawToken)
                .flatMap(r -> envelopeRepo.findById(r.getEnvelopeId()).flatMap(env -> {
                    requireActive(env);
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
                    if (r.requiresOtp() && r.getOtpVerifiedAt() == null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access code not verified"));
                    }
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
                                        // Attribute the audit entry to the signer (identity from the validated token row).
                                        .then(auditService.logAction(AuditAction.SIGNATURE_DOCUMENT_SIGNED, DocumentType.FILE, env.getSourceDocId())
                                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                                        actorResolver.signerAuthentication(r))))
                                        // Single transaction with advance/completion: if sealing (or any other
                                        // finalization step) fails, the SIGNED status rolls back too, so the
                                        // signer can retry — otherwise the envelope is stuck SENT forever with
                                        // an unretryable "already signed" recipient.
                                        .then(Mono.defer(() -> advanceOrComplete(env)))
                                        .as(tx::transactional)
                                        .thenReturn(r);
                            });
                }))
                .flatMap(this::publicView);
    }

    @Override
    public Mono<PublicSignatureView> decline(String rawToken, DeclineSignatureRequest req, String ip) {
        return recipientByToken(rawToken)
                .flatMap(r -> envelopeRepo.findById(r.getEnvelopeId()).flatMap(env -> {
                    requireActive(env);
                    if (!r.isActionable()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Recipient has already " + r.getStatus()));
                    }
                    OffsetDateTime now = OffsetDateTime.now();
                    r.setStatus(SignatureRecipientStatus.DECLINED);
                    r.setDeclineReason(truncate(req == null ? null : req.reason(), 1000));
                    r.setSignerIp(ip);
                    env.setStatus(SignatureEnvelopeStatus.DECLINED);
                    env.setUpdatedAt(now);
                    return recipientRepo.save(r)
                            .then(envelopeRepo.save(env))
                            .then(event(env.getId(), SignatureEventType.RECIPIENT_DECLINED, r.getRecipientEmail(),
                                    env.getOriginalSha256(), ip, r.getDeclineReason()))
                            .then(auditService.logAction(AuditAction.SIGNATURE_ENVELOPE_DECLINED, DocumentType.FILE, env.getSourceDocId())
                                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(actorResolver.signerAuthentication(r))))
                            .as(tx::transactional)
                            .then(notifier.declined(env, r).onErrorResume(e -> Mono.empty()))
                            .doOnSuccess(v -> mailer.sendDeclined(env, r))
                            .thenReturn(r);
                }))
                .flatMap(this::publicView);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Sequential advance + completion
    // ═════════════════════════════════════════════════════════════════════

    private Mono<Void> advanceOrComplete(SignatureEnvelope env) {
        return recipientRepo.findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(env.getId()).collectList()
                .flatMap(recipients -> {
                    boolean allSigned = recipients.stream().filter(SignatureRecipient::isSigner)
                            .allMatch(x -> x.getStatus() == SignatureRecipientStatus.SIGNED);
                    if (allSigned) {
                        return finalizeEnvelope(env, recipients);
                    }
                    if (!env.isSequential()) {
                        return Mono.empty();
                    }
                    boolean currentDone = recipients.stream()
                            .filter(x -> x.isSigner() && x.getOrderIndex() == env.getCurrentOrder())
                            .allMatch(x -> x.getStatus() == SignatureRecipientStatus.SIGNED);
                    if (!currentDone) {
                        return Mono.empty();
                    }
                    int next = recipients.stream().filter(SignatureRecipient::isActionable)
                            .mapToInt(SignatureRecipient::getOrderIndex).min().orElse(env.getCurrentOrder());
                    env.setCurrentOrder(next);
                    env.setUpdatedAt(OffsetDateTime.now());
                    // Mint fresh tokens for the newly unlocked signers and invite them.
                    List<RecipientWithToken> unlocked = recipients.stream()
                            .filter(x -> x.isActionable() && x.getOrderIndex() == next)
                            .map(x -> {
                                String raw = newRawToken();
                                x.setTokenHash(sha256(raw));
                                x.setTokenRevoked(false);
                                return new RecipientWithToken(x, raw);
                            }).toList();
                    return envelopeRepo.save(env)
                            .thenMany(Flux.fromIterable(unlocked).concatMap(rt -> recipientRepo.save(rt.recipient())))
                            .then()
                            .as(tx::transactional)
                            .then(Mono.defer(() -> documentName(env)
                                    .flatMap(name -> dispatchInvitations(env, name, unlocked, next))));
                });
    }

    private Mono<Void> finalizeEnvelope(SignatureEnvelope env, List<SignatureRecipient> recipients) {
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
                                                .then(completionListener.onCompleted(env, signedDoc, seal))
                                                .thenReturn(new CompletionResult(seal, signedDoc));
                                    });
                        }))
                .as(tx::transactional)
                // Post-commit side effects: audit attributed to the requester, notification, mails, thumbnail.
                .flatMap(cr -> actorResolver.requesterAuthentication(env)
                        .flatMap(auth -> auditService.logAction(AuditAction.SIGNATURE_ENVELOPE_COMPLETED, DocumentType.FILE,
                                        cr.signedDoc().getId())
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                        .thenReturn(cr))
                .flatMap(cr -> notifier.completed(env).onErrorResume(e -> Mono.empty()).thenReturn(cr))
                .doOnNext(cr -> emailSignedDocumentToAll(env, recipients, cr.seal().bytes()))
                .doOnNext(cr -> triggerPostProcessing(cr.signedDoc()))
                .doOnSuccess(cr -> log.info("[e-sign] COMPLETED envelope={} signedDoc={} seal={}", env.getId(),
                        env.getSignedDocId(), cr == null ? "?" : cr.seal().provider()))
                .then();
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
        return accessPolicy.resolveSignedDocumentParent(env, src).flatMap(parent -> {
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
                                        String raw = newRawToken();
                                        r.setTokenHash(sha256(raw));
                                        r.setTokenRevoked(false);
                                        r.setReminderCount(r.getReminderCount() + 1);
                                        r.setOtpVerifiedAt(null);
                                        return new RecipientWithToken(r, raw);
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

    private Mono<SignatureRecipient> recipientByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing token"));
        }
        return recipientRepo.findByTokenHash(sha256(rawToken))
                .filter(r -> !r.isTokenRevoked())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid or expired signing link")));
    }

    private Mono<SignatureEnvelope> loadOwned(UUID envelopeId, String userEmail) {
        return envelopeRepo.findById(envelopeId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Envelope not found")))
                .flatMap(env -> accessPolicy.canManage(env, userEmail)
                        .flatMap(ok -> Boolean.TRUE.equals(ok) ? Mono.just(env)
                                : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your envelope"))));
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
        return envelopeRepo.findById(r.getEnvelopeId())
                .flatMap(env -> Mono.zip(
                        documentName(env),
                        fieldRepo.findByEnvelopeIdOrderBySortOrderAscIdAsc(env.getId()).collectList())
                        .map(t -> {
                            List<SignatureField> all = t.getT2();
                            List<SignatureFieldDTO> mine = all.stream().filter(f -> f.getRecipientId().equals(r.getId()))
                                    .map(f -> SignatureFieldDTO.from(f, true)).toList();
                            List<SignatureFieldDTO> others = all.stream()
                                    .filter(f -> !f.getRecipientId().equals(r.getId()) && f.isFilled())
                                    .map(f -> SignatureFieldDTO.from(f, true)).toList();
                            SignatureField first = all.stream().filter(f -> f.getRecipientId().equals(r.getId())
                                    && f.getType() == SignatureFieldType.SIGNATURE).findFirst().orElse(null);
                            boolean myTurn = env.getStatus() == SignatureEnvelopeStatus.SENT
                                    && (!env.isSequential() || r.getOrderIndex() == env.getCurrentOrder());
                            return new PublicSignatureView(env.getTitle(), env.getMessage(), env.getInitiatorEmail(),
                                    t.getT1(), r.getRecipientName(), r.getRecipientEmail(),
                                    env.getStatus(), r.getStatus(), myTurn,
                                    r.getAuthMethod() == null ? SignatureAuthMethod.NONE : r.getAuthMethod(),
                                    r.requiresOtp(), r.getOtpVerifiedAt() != null,
                                    mine, others,
                                    first == null ? r.getFieldPage() : first.getPage(),
                                    first == null ? r.getFieldX() : first.getX(),
                                    first == null ? r.getFieldY() : first.getY(),
                                    first == null ? r.getFieldW() : first.getW(),
                                    first == null ? r.getFieldH() : first.getH(),
                                    first == null ? r.getSignatureImage() : first.getValueImage(),
                                    first == null ? r.getSignatureTyped() : (first.getValueImage() == null ? first.getValue() : null));
                        }));
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
