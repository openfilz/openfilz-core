package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.dto.signature.ApplySignatureRequest;
import org.openfilz.dms.dto.signature.PublicSignatureView;
import org.openfilz.dms.dto.signature.SignatureFieldDTO;
import org.openfilz.dms.dto.signature.SignatureFieldValue;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
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
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.signature.SignatureAccessPolicy;
import org.openfilz.dms.service.signature.SignatureActorResolver;
import org.openfilz.dms.service.signature.SignatureCompletionListener;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.openfilz.dms.service.signature.SignatureNotifier;
import org.openfilz.dms.service.signature.SignatureOtpSender;
import org.openfilz.dms.service.signature.SignatureSealer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a signing link may reveal and do, by state of the recipient and of the envelope: the
 * OTP gate in front of the document and the other signers' data, dead links (terminal
 * envelope, revoked token) answering 410 to every action, and the OTP throttles.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignatureServiceImplPublicAccessTest {

    @Mock SignatureEnvelopeRepository envelopeRepo;
    @Mock SignatureRecipientRepository recipientRepo;
    @Mock SignatureFieldRepository fieldRepo;
    @Mock SignatureEventRepository eventRepo;
    @Mock DocumentRepository documentRepository;
    @Mock StorageService storageService;
    @Mock SignaturePdfService pdfService;
    @Mock AuditService auditService;
    @Mock TransactionalOperator tx;
    @Mock SignatureAccessPolicy accessPolicy;
    @Mock SignatureActorResolver actorResolver;
    @Mock SignatureNotifier notifier;
    @Mock SignatureMailer mailer;
    @Mock SignatureSealer sealer;
    @Mock SignatureCompletionListener completionListener;
    @Mock SignatureOtpSender otpSender;
    @Mock ObjectProvider<MetadataPostProcessor> metadataPostProcessorProvider;

    private final SignatureProperties props = new SignatureProperties();
    private SignatureServiceImpl service;

    static final String TOKEN = "raw-token";
    static final String TOKEN_HASH = "token-hash";
    static final String OTHER_IMAGE = "data:image/png;base64,T1RIRVI=";

    UUID envId = UUID.randomUUID();
    UUID docId = UUID.randomUUID();
    SignatureEnvelope env;
    SignatureRecipient me;
    SignatureRecipient other;
    SignatureField myField;
    SignatureField otherField;
    Document srcDoc;

    @BeforeEach
    void setUp() {
        service = new SignatureServiceImpl(envelopeRepo, recipientRepo, fieldRepo, eventRepo, documentRepository,
                storageService, pdfService, auditService, tx, props, new CommonProperties(), accessPolicy, actorResolver,
                notifier, mailer, sealer, sealer, completionListener, List.of(otpSender), metadataPostProcessorProvider);

        env = SignatureEnvelope.builder().id(envId).title("Contract").message("please sign").initiatorEmail("boss@x.io")
                .sourceDocId(docId).status(SignatureEnvelopeStatus.SENT).sequential(false).currentOrder(0)
                .expiresAt(OffsetDateTime.now().plusDays(10)).build();
        me = SignatureRecipient.builder().id(UUID.randomUUID()).envelopeId(envId).recipientName("Me").recipientEmail("me@x.io")
                .role(SignatureRecipientRole.SIGNER).authMethod(SignatureAuthMethod.NONE).status(SignatureRecipientStatus.VIEWED)
                .tokenHash(TOKEN_HASH).build();
        other = SignatureRecipient.builder().id(UUID.randomUUID()).envelopeId(envId).recipientName("Other").recipientEmail("other@x.io")
                .role(SignatureRecipientRole.SIGNER).authMethod(SignatureAuthMethod.NONE).status(SignatureRecipientStatus.SIGNED)
                .tokenHash("other-hash").build();
        myField = SignatureField.builder().id(UUID.randomUUID()).envelopeId(envId).recipientId(me.getId())
                .type(SignatureFieldType.SIGNATURE).page(0).x(0.1).y(0.1).w(0.3).h(0.1).required(true).sortOrder(0).build();
        otherField = SignatureField.builder().id(UUID.randomUUID()).envelopeId(envId).recipientId(other.getId())
                .type(SignatureFieldType.SIGNATURE).page(0).x(0.5).y(0.1).w(0.3).h(0.1).required(true).sortOrder(1)
                .value(null).valueImage(OTHER_IMAGE).filledAt(OffsetDateTime.now().minusHours(1)).build();
        srcDoc = Document.builder().id(docId).name("contract.pdf").storagePath("p/contract.pdf").contentType("application/pdf").build();

        // sha256 is delegated to the pdf service — make it a readable, deterministic hash.
        when(pdfService.sha256Hex(any())).thenAnswer(inv -> "sha:" + new String((byte[]) inv.getArgument(0), StandardCharsets.UTF_8));
        when(recipientRepo.findByTokenHash("sha:" + TOKEN)).thenReturn(Mono.just(me));
        when(envelopeRepo.findById(envId)).thenReturn(Mono.just(env));
        when(envelopeRepo.findByIdForUpdate(envId)).thenReturn(Mono.just(env));
        when(fieldRepo.findByEnvelopeIdOrderBySortOrderAscIdAsc(envId)).thenReturn(Flux.just(myField, otherField));
        when(fieldRepo.findByRecipientIdOrderBySortOrderAscIdAsc(me.getId())).thenReturn(Flux.just(myField));
        when(documentRepository.findByIdAndActive(docId, true)).thenReturn(Mono.just(srcDoc));
        doReturn(Mono.just(new ByteArrayResource("%PDF".getBytes()))).when(storageService).loadFile("p/contract.pdf");
        when(tx.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        when(recipientRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(eventRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(otpSender.supports(SignatureAuthMethod.EMAIL_OTP)).thenReturn(true);
        when(otpSender.send(any(), any(), anyString(), anyInt())).thenReturn(Mono.empty());
    }

    private void otpRecipient() {
        me.setAuthMethod(SignatureAuthMethod.EMAIL_OTP);
    }

    private static ResponseStatusException statusOf(Throwable t) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        return (ResponseStatusException) t;
    }

    // ── OTP gate in front of the document ────────────────────────────────

    @Test
    void otpRecipient_beforeVerification_getsTheMinimalView_andNoDocument() {
        otpRecipient();

        PublicSignatureView v = service.getByToken(TOKEN).block();
        assertThat(v.otpRequired()).isTrue();
        assertThat(v.otpVerified()).isFalse();
        assertThat(v.envelopeTitle()).isEqualTo("Contract");
        assertThat(v.recipientName()).isEqualTo("Me");
        assertThat(v.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.SENT);
        assertThat(v.myTurn()).isTrue();
        // nothing about the document or the other signers
        assertThat(v.documentName()).isNull();
        assertThat(v.message()).isNull();
        assertThat(v.fields()).isEmpty();
        assertThat(v.otherFields()).isEmpty();
        assertThat(v.signatureImage()).isNull();
        assertThat(v.fieldPage()).isNull();

        StepVerifier.create(service.loadDocumentByToken(TOKEN))
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
        verify(storageService, never()).loadFile(any());

        // /viewed still records the open but answers the same minimal view
        PublicSignatureView viewed = service.recordView(TOKEN, "1.2.3.4", "ua").block();
        assertThat(viewed.documentName()).isNull();
        assertThat(viewed.fields()).isEmpty();
    }

    @Test
    void otpRecipient_afterVerification_getsTheFullView_andTheDocument() {
        otpRecipient();
        me.setOtpVerifiedAt(OffsetDateTime.now().minusMinutes(5));

        PublicSignatureView v = service.getByToken(TOKEN).block();
        assertThat(v.otpRequired()).isTrue();
        assertThat(v.otpVerified()).isTrue();
        assertThat(v.documentName()).isEqualTo("contract.pdf");
        assertThat(v.message()).isEqualTo("please sign");
        assertThat(v.fields()).extracting(SignatureFieldDTO::id).containsExactly(myField.getId());

        Resource pdf = service.loadDocumentByToken(TOKEN).block();
        assertThat(pdf).isNotNull();
    }

    @Test
    void otpVerification_agesOut_afterTheConfiguredWindow() {
        otpRecipient();
        props.getOtp().setVerifiedFor(Duration.ofHours(1));
        me.setOtpVerifiedAt(OffsetDateTime.now().minusHours(2));

        PublicSignatureView v = service.getByToken(TOKEN).block();
        assertThat(v.otpVerified()).isFalse();
        assertThat(v.fields()).isEmpty();
        StepVerifier.create(service.loadDocumentByToken(TOKEN))
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
        StepVerifier.create(service.applySignature(TOKEN, new ApplySignatureRequest(null, "Me", null), "ip", "ua"))
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        // 0 = never ages out
        props.getOtp().setVerifiedFor(Duration.ZERO);
        assertThat(service.getByToken(TOKEN).block().otpVerified()).isTrue();
    }

    // ── Other signers' data ──────────────────────────────────────────────

    @Test
    void otherSignersFields_comeWithoutValuesOrImages() {
        PublicSignatureView v = service.getByToken(TOKEN).block();

        assertThat(v.otherFields()).hasSize(1);
        SignatureFieldDTO theirs = v.otherFields().getFirst();
        assertThat(theirs.id()).isEqualTo(otherField.getId());
        assertThat(theirs.recipientId()).isEqualTo(other.getId());
        assertThat(theirs.page()).isZero();
        assertThat(theirs.x()).isEqualTo(0.5);
        assertThat(theirs.filledAt()).isNotNull();
        assertThat(theirs.valueImage()).as("another signer's signature image must not leak").isNull();
        assertThat(theirs.value()).isNull();
        // my own field keeps its (empty so far) value slots and my legacy projection is present
        assertThat(v.fields()).hasSize(1);
        assertThat(v.fieldPage()).isZero();
    }

    // ── Dead links: terminal envelope, revoked token ─────────────────────

    @Test
    void terminalEnvelope_answers410ToEveryAction_andAStatusOnlyView() {
        for (SignatureEnvelopeStatus terminal : List.of(SignatureEnvelopeStatus.COMPLETED, SignatureEnvelopeStatus.CANCELLED,
                SignatureEnvelopeStatus.DECLINED, SignatureEnvelopeStatus.EXPIRED)) {
            env.setStatus(terminal);
            otpRecipient();

            expectGone(service.loadDocumentByToken(TOKEN));
            expectGone(service.applySignature(TOKEN, new ApplySignatureRequest(null, "Me", null), "ip", "ua"));
            expectGone(service.requestOtp(TOKEN));
            expectGone(service.verifyOtp(TOKEN, "123456", "ip"));
            expectGone(service.decline(TOKEN, null, "ip"));

            PublicSignatureView v = service.getByToken(TOKEN).block();
            assertThat(v.envelopeStatus()).isEqualTo(terminal);
            assertThat(v.myTurn()).isFalse();
            assertThat(v.documentName()).isNull();
            assertThat(v.fields()).isEmpty();
            assertThat(v.otherFields()).isEmpty();
            // /viewed on a closed envelope records nothing
            service.recordView(TOKEN, "ip", "ua").block();
        }
        verify(storageService, never()).loadFile(any());
        verify(recipientRepo, never()).save(any());
        verify(otpSender, never()).send(any(), any(), anyString(), anyInt());
    }

    @Test
    void envelopePastItsDeadline_isExpiredForTheSigner_evenBeforeTheSweeperRan() {
        env.setExpiresAt(OffsetDateTime.now().minusMinutes(1));   // status column still SENT

        expectGone(service.loadDocumentByToken(TOKEN));
        expectGone(service.applySignature(TOKEN, new ApplySignatureRequest(null, "Me", null), "ip", "ua"));
        PublicSignatureView v = service.getByToken(TOKEN).block();
        assertThat(v.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.EXPIRED);
        assertThat(v.fields()).isEmpty();
    }

    @Test
    void revokedToken_answers410ToEveryAction_andAStatusOnlyView() {
        me.setTokenRevoked(true);

        expectGone(service.loadDocumentByToken(TOKEN));
        expectGone(service.applySignature(TOKEN, new ApplySignatureRequest(null, "Me", null), "ip", "ua"));
        expectGone(service.decline(TOKEN, null, "ip"));
        PublicSignatureView v = service.getByToken(TOKEN).block();
        assertThat(v.documentName()).isNull();
        assertThat(v.fields()).isEmpty();
        assertThat(v.recipientStatus()).isEqualTo(SignatureRecipientStatus.VIEWED);
    }

    @Test
    void unknownToken_is404() {
        when(recipientRepo.findByTokenHash(any())).thenReturn(Mono.empty());
        StepVerifier.create(service.getByToken("nope"))
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void draftEnvelope_answers409ToActions() {
        env.setStatus(SignatureEnvelopeStatus.DRAFT);
        StepVerifier.create(service.loadDocumentByToken(TOKEN))
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    private static void expectGone(Mono<?> action) {
        StepVerifier.create(action)
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.GONE))
                .verify();
    }

    // ── OTP hardening ────────────────────────────────────────────────────

    @Test
    void parallelVerifyAttempts_cannotExceedTheCap() {
        otpRecipient();
        props.getOtp().setMaxAttempts(5);
        me.setOtpHash("sha:111111");
        me.setOtpExpiresAt(OffsetDateTime.now().plusMinutes(5));
        // The repository mock plays the database's conditional UPDATE: a slot while < max, then nothing.
        AtomicInteger stored = new AtomicInteger();
        when(recipientRepo.reserveOtpAttempt(eq(me.getId()), eq(5))).thenAnswer(inv ->
                Mono.fromSupplier(() -> stored.getAndUpdate(n -> n < 5 ? n + 1 : n) < 5 ? 1 : 0));

        int parallel = 40;
        List<Integer> statuses = Flux.range(0, parallel)
                .flatMap(i -> service.verifyOtp(TOKEN, "000000", "ip")
                        .map(v -> 200)
                        .onErrorResume(e -> Mono.just(statusOf(e).getStatusCode().value()))
                        .subscribeOn(Schedulers.parallel()), parallel)
                .collectList().block();

        assertThat(statuses).hasSize(parallel);
        assertThat(statuses.stream().filter(s -> s == 403).count()).as("guesses that got a slot").isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 429).count()).as("guesses refused at the cap").isEqualTo(parallel - 5);
        assertThat(stored.get()).isEqualTo(5);
        verify(recipientRepo, never()).save(any());   // a wrong guess never rewrites the row
    }

    @Test
    void verifyOtp_rightCode_consumesItAndMarksVerified() {
        otpRecipient();
        me.setOtpHash("sha:424242");
        me.setOtpExpiresAt(OffsetDateTime.now().plusMinutes(5));
        when(recipientRepo.reserveOtpAttempt(eq(me.getId()), anyInt())).thenReturn(Mono.just(1));

        PublicSignatureView v = service.verifyOtp(TOKEN, " 424242 ", "ip").block();
        assertThat(v.otpVerified()).isTrue();
        assertThat(v.fields()).hasSize(1);           // own-action response carries the signer's fields
        assertThat(me.getOtpHash()).isNull();
        assertThat(me.getOtpVerifiedAt()).isNotNull();
        assertThat(me.getOtpAttempts()).isEqualTo(1);
    }

    @Test
    void verifyOtp_withNoCodeIssued_is410_withoutTouchingTheCounter() {
        otpRecipient();
        StepVerifier.create(service.verifyOtp(TOKEN, "000000", "ip"))
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.GONE))
                .verify();
        verify(recipientRepo, never()).reserveOtpAttempt(any(), anyInt());
    }

    @Test
    void requestOtp_isRefusedDuringTheCooldown() {
        otpRecipient();
        props.getOtp().setRequestCooldown(Duration.ofSeconds(60));
        me.setOtpRequestedAt(OffsetDateTime.now().minusSeconds(10));
        me.setOtpRequestCount(1);

        StepVerifier.create(service.requestOtp(TOKEN))
                .expectErrorSatisfies(e -> {
                    assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(statusOf(e).getReason()).contains("wait");
                })
                .verify();
        verify(recipientRepo, never()).issueOtp(any(), any(), any(), any(), any(), anyInt());
        verify(otpSender, never()).send(any(), any(), anyString(), anyInt());
    }

    @Test
    void requestOtp_afterTheCooldown_issuesACode_andCountsIt() {
        otpRecipient();
        props.getOtp().setRequestCooldown(Duration.ofSeconds(60));
        me.setOtpRequestedAt(OffsetDateTime.now().minusSeconds(90));
        me.setOtpRequestCount(1);
        when(recipientRepo.issueOtp(eq(me.getId()), anyString(), any(), any(), any(), eq(10))).thenReturn(Mono.just(1));

        service.requestOtp(TOKEN).block();

        verify(otpSender).send(eq(env), eq(me), anyString(), eq(10));
        assertThat(me.getOtpRequestCount()).isEqualTo(2);
        assertThat(me.getOtpAttempts()).isZero();
        assertThat(me.getOtpHash()).isNotNull();
    }

    @Test
    void requestOtp_hasALifetimeCapPerLink() {
        otpRecipient();
        props.getOtp().setRequestCooldown(Duration.ZERO);
        props.getOtp().setMaxRequests(10);
        me.setOtpRequestCount(10);

        StepVerifier.create(service.requestOtp(TOKEN))
                .expectErrorSatisfies(e -> {
                    assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(statusOf(e).getReason()).contains("new link");
                })
                .verify();
        verify(otpSender, never()).send(any(), any(), anyString(), anyInt());
    }

    @Test
    void requestOtp_losingTheRaceOnTheConditionalUpdate_is429_andSendsNothing() {
        otpRecipient();
        props.getOtp().setRequestCooldown(Duration.ofSeconds(60));
        when(recipientRepo.issueOtp(eq(me.getId()), anyString(), any(), any(), any(), anyInt())).thenReturn(Mono.just(0));

        StepVerifier.create(service.requestOtp(TOKEN))
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .verify();
        verify(otpSender, never()).send(any(), any(), anyString(), anyInt());
    }

    @Test
    void requestOtp_withoutOtp_is409() {
        StepVerifier.create(service.requestOtp(TOKEN))
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    @Test
    void decline_requiresThePassedOtpStep() {
        otpRecipient();
        StepVerifier.create(service.decline(TOKEN, null, "ip"))
                .expectErrorSatisfies(e -> assertThat(statusOf(e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
        verify(envelopeRepo, never()).save(any());
    }

    @Test
    void signRequest_withValues_isTheOnlyWayToSeeOnesOwnSignature() {
        // Sanity check on the own-action response: a plain (no OTP) recipient signing a one-signer
        // envelope gets their filled field back even though the envelope is now COMPLETED.
        when(recipientRepo.findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(envId)).thenReturn(Flux.just(me));
        when(fieldRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(eventRepo.findByEnvelopeIdOrderByCreatedAtAsc(envId)).thenReturn(Flux.empty());
        when(pdfService.buildStampedDocument(any(), any(), any(), any(), any())).thenReturn(new byte[]{1});
        when(sealer.seal(any(), any())).thenReturn(Mono.just(SignatureSealer.SealResult.plain(new byte[]{2}, "test")));
        when(storageService.getUniqueStorageFileName(any())).thenReturn("signed.pdf");
        when(storageService.saveData(any(), any())).thenReturn(Mono.empty());
        when(accessPolicy.resolveSignedDocumentParent(any(), any())).thenReturn(Mono.just(java.util.Optional.empty()));
        when(accessPolicy.afterSignedDocumentPersisted(any(), any())).thenReturn(Mono.empty());
        when(documentRepository.save(any())).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return Mono.just(d);
        });
        when(envelopeRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recipientRepo.revokeTokens(envId)).thenReturn(Mono.just(1));
        when(completionListener.onCompleted(any(), any(), any())).thenReturn(Mono.empty());
        when(actorResolver.requesterAuthentication(env)).thenReturn(Mono.just(org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class)));
        when(actorResolver.signerAuthentication(me)).thenReturn(org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class));
        when(auditService.logAction(any(), any(), any())).thenReturn(Mono.empty());
        when(notifier.completed(any())).thenReturn(Mono.empty());
        when(metadataPostProcessorProvider.getIfAvailable()).thenReturn(null);
        // The sign response re-reads the envelope: by then it is COMPLETED.
        when(fieldRepo.findByEnvelopeIdOrderBySortOrderAscIdAsc(envId)).thenAnswer(inv -> Flux.just(myField, otherField));

        PublicSignatureView v = service.applySignature(TOKEN,
                new ApplySignatureRequest(null, null, List.of(new SignatureFieldValue(myField.getId(), null, "data:image/png;base64,TUU="))),
                "ip", "ua").block();

        assertThat(v.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(v.recipientStatus()).isEqualTo(SignatureRecipientStatus.SIGNED);
        assertThat(v.fields()).hasSize(1);
        assertThat(v.signatureImage()).isEqualTo("data:image/png;base64,TUU=");
        assertThat(v.otherFields().getFirst().valueImage()).isNull();
        verify(recipientRepo).revokeTokens(envId);

        // ...and once the link is re-opened the view is status-only.
        me.setTokenRevoked(true);
        PublicSignatureView reopened = service.getByToken(TOKEN).block();
        assertThat(reopened.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(reopened.recipientStatus()).isEqualTo(SignatureRecipientStatus.SIGNED);
        assertThat(reopened.fields()).isEmpty();
        assertThat(reopened.signatureImage()).isNull();
    }
}
