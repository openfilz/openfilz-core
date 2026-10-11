package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.dto.signature.ApplySignatureRequest;
import org.openfilz.dms.dto.signature.PublicSignatureView;
import org.openfilz.dms.dto.signature.SignatureFieldValue;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
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
import org.springframework.security.core.Authentication;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Envelope finalization and the audit chain: the chained audit insert takes a global
 * advisory lock until commit, so the audit rows must be written <em>after</em> the slow work
 * (stamp, seal, store) and the side effects (mails, notifications) after the commit — while
 * a failed seal or completion listener still fails the whole signature so nothing is wedged.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignatureServiceImplFinalizationTest {

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

    static final String TOKEN = "tok";
    static final byte[] STAMPED = {7, 7};
    static final byte[] SEALED = {8, 8, 8};

    UUID envId = UUID.randomUUID();
    UUID docId = UUID.randomUUID();
    SignatureEnvelope env;
    SignatureRecipient alice;
    SignatureRecipient bob;
    SignatureField aliceField;
    SignatureField bobField;
    Document srcDoc;
    ApplySignatureRequest aliceSigns;

    @BeforeEach
    void setUp() {
        service = new SignatureServiceImpl(envelopeRepo, recipientRepo, fieldRepo, eventRepo, documentRepository,
                storageService, pdfService, auditService, tx, props, new CommonProperties(), accessPolicy, actorResolver,
                notifier, mailer, sealer, sealer, completionListener, List.of(otpSender), metadataPostProcessorProvider);

        env = SignatureEnvelope.builder().id(envId).title("Deal").initiatorEmail("boss@x.io").sourceDocId(docId)
                .status(SignatureEnvelopeStatus.SENT).sequential(false).currentOrder(0).originalSha256("orig")
                .expiresAt(OffsetDateTime.now().plusDays(5)).build();
        alice = SignatureRecipient.builder().id(UUID.randomUUID()).envelopeId(envId).recipientName("Alice").recipientEmail("alice@x.io")
                .role(SignatureRecipientRole.SIGNER).authMethod(SignatureAuthMethod.NONE).status(SignatureRecipientStatus.VIEWED)
                .tokenHash("sha:" + TOKEN).build();
        bob = SignatureRecipient.builder().id(UUID.randomUUID()).envelopeId(envId).recipientName("Bob").recipientEmail("bob@x.io")
                .role(SignatureRecipientRole.SIGNER).authMethod(SignatureAuthMethod.NONE).status(SignatureRecipientStatus.SIGNED)
                .tokenHash("sha:bob").build();
        aliceField = SignatureField.builder().id(UUID.randomUUID()).envelopeId(envId).recipientId(alice.getId())
                .type(SignatureFieldType.SIGNATURE).page(0).x(0.1).y(0.1).w(0.3).h(0.1).required(true).sortOrder(0).build();
        bobField = SignatureField.builder().id(UUID.randomUUID()).envelopeId(envId).recipientId(bob.getId())
                .type(SignatureFieldType.SIGNATURE).page(0).x(0.5).y(0.1).w(0.3).h(0.1).required(true).sortOrder(1)
                .valueImage("data:image/png;base64,Qk9C").filledAt(OffsetDateTime.now()).build();
        srcDoc = Document.builder().id(docId).name("deal.pdf").storagePath("p/deal.pdf").contentType("application/pdf").build();
        aliceSigns = new ApplySignatureRequest(null, null,
                List.of(new SignatureFieldValue(aliceField.getId(), null, "data:image/png;base64,QUxJQ0U=")));

        when(pdfService.sha256Hex(any())).thenAnswer(inv -> "sha:" + new String((byte[]) inv.getArgument(0), StandardCharsets.UTF_8));
        when(recipientRepo.findByTokenHash("sha:" + TOKEN)).thenReturn(Mono.just(alice));
        when(envelopeRepo.findById(envId)).thenReturn(Mono.just(env));
        when(envelopeRepo.findByIdForUpdate(envId)).thenReturn(Mono.just(env));
        when(envelopeRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recipientRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recipientRepo.findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(envId)).thenAnswer(inv -> Flux.just(alice, bob));
        when(recipientRepo.revokeTokens(envId)).thenReturn(Mono.just(2));
        when(fieldRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(fieldRepo.findByRecipientIdOrderBySortOrderAscIdAsc(alice.getId())).thenReturn(Flux.just(aliceField));
        when(fieldRepo.findByEnvelopeIdOrderBySortOrderAscIdAsc(envId)).thenAnswer(inv -> Flux.just(aliceField, bobField));
        when(eventRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(eventRepo.findByEnvelopeIdOrderByCreatedAtAsc(envId)).thenReturn(Flux.empty());
        when(documentRepository.findByIdAndActive(docId, true)).thenReturn(Mono.just(srcDoc));
        when(documentRepository.save(any())).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return Mono.just(d);
        });
        doReturn(Mono.just(new ByteArrayResource("%PDF-orig".getBytes()))).when(storageService).loadFile("p/deal.pdf");
        when(storageService.getUniqueStorageFileName(any())).thenReturn("Deal-signed.pdf");
        when(storageService.saveData(any(), any())).thenReturn(Mono.empty());
        when(pdfService.buildStampedDocument(any(), any(), any(), any(), any())).thenReturn(STAMPED);
        when(sealer.seal(STAMPED, env)).thenReturn(Mono.just(SignatureSealer.SealResult.plain(SEALED, "test-seal")));
        when(sealer.id()).thenReturn("test-seal");
        when(accessPolicy.resolveSignedDocumentParent(any(), any())).thenReturn(Mono.just(Optional.empty()));
        when(accessPolicy.afterSignedDocumentPersisted(any(), any())).thenReturn(Mono.empty());
        when(completionListener.onCompleted(any(), any(), any())).thenReturn(Mono.empty());
        when(actorResolver.signerAuthentication(any())).thenReturn(mock(Authentication.class));
        when(actorResolver.requesterAuthentication(any())).thenReturn(Mono.just(mock(Authentication.class)));
        when(auditService.logAction(any(), any(), any())).thenReturn(Mono.empty());
        when(notifier.completed(any())).thenReturn(Mono.empty());
        when(notifier.requested(any(), any())).thenReturn(Mono.empty());
        when(metadataPostProcessorProvider.getIfAvailable()).thenReturn(null);
        when(tx.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void lastSignature_auditsOnlyAfterStampSealAndStore_andMailsOnlyAfterTheAudit() {
        PublicSignatureView v = service.applySignature(TOKEN, aliceSigns, "1.1.1.1", "ua").block();

        assertThat(v.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(env.getStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(env.getSignedSha256()).isEqualTo("sha:" + new String(SEALED, StandardCharsets.UTF_8));

        InOrder order = inOrder(pdfService, sealer, storageService, recipientRepo, completionListener, auditService, notifier, mailer);
        order.verify(pdfService).buildStampedDocument(any(), eq(env), any(), any(), any());
        order.verify(sealer).seal(STAMPED, env);
        order.verify(storageService).saveData(eq("Deal-signed.pdf"), any());
        order.verify(recipientRepo).revokeTokens(envId);
        order.verify(completionListener).onCompleted(eq(env), any(), any());
        // audit rows: signer's, then completion — both after the slow work, right before the commit
        order.verify(auditService).logAction(AuditAction.SIGNATURE_DOCUMENT_SIGNED, DocumentType.FILE, docId);
        order.verify(auditService).logAction(eq(AuditAction.SIGNATURE_ENVELOPE_COMPLETED), eq(DocumentType.FILE), any(UUID.class));
        // side effects after the transaction
        order.verify(notifier).completed(env);
        order.verify(mailer).sendCompleted(eq(env), eq("boss@x.io"), any(), any(), eq(SEALED), anyString());
        verify(mailer).sendCompleted(eq(env), eq("alice@x.io"), eq("Alice"), any(), eq(SEALED), anyString());
        verify(mailer).sendCompleted(eq(env), eq("bob@x.io"), eq("Bob"), any(), eq(SEALED), anyString());
    }

    @Test
    void sealFailure_failsTheSignature_withNoAuditNoRevocationNoMail() {
        when(sealer.seal(STAMPED, env)).thenReturn(Mono.error(new IllegalStateException("archiving down")));

        StepVerifier.create(service.applySignature(TOKEN, aliceSigns, "1.1.1.1", "ua"))
                .expectErrorMessage("archiving down")
                .verify();

        // the transaction (joined by all of the above) rolls back; nothing beyond the seal happened
        assertThat(env.getStatus()).isEqualTo(SignatureEnvelopeStatus.SENT);
        verify(storageService, never()).saveData(any(), any());
        verify(recipientRepo, never()).revokeTokens(any());
        verify(auditService, never()).logAction(any(), any(), any());
        verify(mailer, never()).sendCompleted(any(), any(), any(), any(), any(), any());
        verify(notifier, never()).completed(any());
    }

    @Test
    void completionListenerFailure_failsTheSignature_beforeAnyAuditRow() {
        when(completionListener.onCompleted(any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("archive store down")));

        StepVerifier.create(service.applySignature(TOKEN, aliceSigns, "1.1.1.1", "ua"))
                .expectErrorMessage("archive store down")
                .verify();

        verify(auditService, never()).logAction(any(), any(), any());
        verify(mailer, never()).sendCompleted(any(), any(), any(), any(), any(), any());
    }

    @Test
    void notLastSignature_auditsTheSignatureOnly_andNeverSeals() {
        bob.setStatus(SignatureRecipientStatus.VIEWED);

        PublicSignatureView v = service.applySignature(TOKEN, aliceSigns, "1.1.1.1", "ua").block();

        assertThat(v.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.SENT);
        assertThat(v.recipientStatus()).isEqualTo(SignatureRecipientStatus.SIGNED);
        verify(auditService).logAction(AuditAction.SIGNATURE_DOCUMENT_SIGNED, DocumentType.FILE, docId);
        verify(auditService, never()).logAction(eq(AuditAction.SIGNATURE_ENVELOPE_COMPLETED), any(), any());
        verify(sealer, never()).seal(any(), any());
        verify(recipientRepo, never()).revokeTokens(any());
        verify(envelopeRepo).findByIdForUpdate(envId);   // the envelope row is locked for the signing transaction
    }

    @Test
    void sequentialAdvance_invitesTheNextGroupOnlyAfterTheTransaction() {
        env.setSequential(true);
        env.setCurrentOrder(0);
        alice.setOrderIndex(0);
        bob.setOrderIndex(1);
        bob.setStatus(SignatureRecipientStatus.PENDING);

        service.applySignature(TOKEN, aliceSigns, "1.1.1.1", "ua").block();

        assertThat(env.getCurrentOrder()).isEqualTo(1);
        assertThat(bob.getTokenHash()).isNotEqualTo("sha:bob");   // fresh token for the unlocked signer
        assertThat(bob.getOtpRequestCount()).isZero();
        InOrder order = inOrder(auditService, mailer);
        order.verify(auditService).logAction(AuditAction.SIGNATURE_DOCUMENT_SIGNED, DocumentType.FILE, docId);
        order.verify(mailer).sendRequest(eq(env), eq(bob), eq("deal.pdf"), anyString());
    }

    @Test
    void healingAWedgedEnvelope_finalizesThenAuditsThenMails() {
        // Historical wedge: every signer SIGNED, envelope still SENT. Alice re-opens her link.
        alice.setStatus(SignatureRecipientStatus.SIGNED);

        PublicSignatureView healed = service.recordView(TOKEN, "1.1.1.1", "ua").block();

        assertThat(healed.recipientStatus()).isEqualTo(SignatureRecipientStatus.SIGNED);
        assertThat(healed.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        InOrder order = inOrder(sealer, recipientRepo, auditService, mailer);
        order.verify(sealer).seal(STAMPED, env);
        order.verify(recipientRepo).revokeTokens(envId);
        order.verify(auditService).logAction(eq(AuditAction.SIGNATURE_ENVELOPE_COMPLETED), eq(DocumentType.FILE), any(UUID.class));
        order.verify(mailer).sendCompleted(eq(env), eq("alice@x.io"), any(), any(), eq(SEALED), anyString());
        verify(auditService, never()).logAction(eq(AuditAction.SIGNATURE_DOCUMENT_SIGNED), any(), any());
    }

    @Test
    void healing_swallowsAFailedRetry_andStillRendersTheCurrentState() {
        alice.setStatus(SignatureRecipientStatus.SIGNED);
        when(sealer.seal(STAMPED, env)).thenReturn(Mono.error(new IllegalStateException("still down")));

        PublicSignatureView view = service.recordView(TOKEN, "1.1.1.1", "ua").block();

        assertThat(view.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.SENT);
        assertThat(view.recipientStatus()).isEqualTo(SignatureRecipientStatus.SIGNED);
        verify(auditService, never()).logAction(any(), any(), any());
        verify(mailer, never()).sendCompleted(any(), any(), any(), any(), any(), any());
    }
}
