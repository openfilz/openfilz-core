package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureEvent;
import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.entity.SignatureRecipient;
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
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the pure helpers of {@link SignatureServiceImpl}. The envelope
 * lifecycle itself is covered by the e2e ITs under {@code e2e/signature}.
 */
@ExtendWith(MockitoExtension.class)
class SignatureServiceImplTest {

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
    @Mock SignatureSealer primarySealer;
    @Mock SignatureSealer coreSealer;
    @Mock SignatureCompletionListener completionListener;
    @Mock SignatureOtpSender otpSender;
    @Mock ObjectProvider<MetadataPostProcessor> metadataPostProcessorProvider;

    private final SignatureProperties props = new SignatureProperties();
    private final CommonProperties commonProperties = new CommonProperties();

    private SignatureServiceImpl service(SignatureSealer sealer, SignatureSealer core) {
        return new SignatureServiceImpl(envelopeRepo, recipientRepo, fieldRepo, eventRepo, documentRepository,
                storageService, pdfService, auditService, tx, props, commonProperties, accessPolicy, actorResolver,
                notifier, mailer, sealer, core, completionListener, List.of(otpSender), metadataPostProcessorProvider);
    }

    private SignatureServiceImpl service;

    @BeforeEach
    void setUp() {
        service = service(primarySealer, coreSealer);
    }

    // ── token / otp generators ────────────────────────────────────────────

    @Test
    void newRawToken_is32RandomBytes_urlSafeBase64_withoutPadding_andUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String t = SignatureServiceImpl.newRawToken();
            assertThat(t).hasSize(43).matches("^[A-Za-z0-9_-]+$").doesNotContain("=");
            assertThat(Base64.getUrlDecoder().decode(t)).hasSize(32);
            assertThat(seen.add(t)).as("token uniqueness").isTrue();
        }
    }

    @Test
    void newOtpCode_hasRequestedLength_andOnlyDigits() {
        for (int len : new int[]{4, 6, 8}) {
            for (int i = 0; i < 50; i++) {
                assertThat(SignatureServiceImpl.newOtpCode(len)).hasSize(len).matches("^[0-9]+$");
            }
        }
        assertThat(SignatureServiceImpl.newOtpCode(0)).isEmpty();
        // leading zeros are preserved (it is a string, not a number)
        Set<Character> firstChars = new HashSet<>();
        for (int i = 0; i < 500; i++) firstChars.add(SignatureServiceImpl.newOtpCode(6).charAt(0));
        assertThat(firstChars).contains('0');
    }

    // ── signLink ──────────────────────────────────────────────────────────

    @Test
    void signLink_prefersSignaturePropsBaseUrl_andAppendsSlash() {
        props.setWebBaseUrl("https://sign.example.com/app");
        commonProperties.setWebPublicBaseUrl("https://ignored.example.com/");
        assertThat(service.signLink("tok")).isEqualTo("https://sign.example.com/app/sign?token=tok");

        props.setWebBaseUrl("https://sign.example.com/app/");
        assertThat(service.signLink("tok")).isEqualTo("https://sign.example.com/app/sign?token=tok");
    }

    @Test
    void signLink_fallsBackToCommonWebPublicBaseUrl() {
        props.setWebBaseUrl("   ");
        commonProperties.setWebPublicBaseUrl("https://app.example.com");
        assertThat(service.signLink("abc")).isEqualTo("https://app.example.com/sign?token=abc");
        props.setWebBaseUrl(null);
        assertThat(service.signLink("abc")).isEqualTo("https://app.example.com/sign?token=abc");
    }

    @Test
    void signLink_lastResortLocalhost() {
        props.setWebBaseUrl("");
        commonProperties.setWebPublicBaseUrl("");
        assertThat(service.signLink("x")).isEqualTo("http://localhost:4200/sign?token=x");
        commonProperties.setWebPublicBaseUrl(null);
        assertThat(service.signLink("x")).isEqualTo("http://localhost:4200/sign?token=x");
    }

    // ── stampAndSeal ──────────────────────────────────────────────────────

    private static final byte[] ORIGINAL = {1, 2, 3};
    private static final byte[] STAMPED = {9, 9, 9};
    private final SignatureEnvelope env = SignatureEnvelope.builder().id(UUID.randomUUID()).build();
    private final List<SignatureRecipient> recipients = List.of();
    private final List<SignatureField> fields = List.of();
    private final List<SignatureEvent> events = List.of();

    @Test
    void stampAndSeal_happyPath_usesPrimarySealer() {
        when(pdfService.buildStampedDocument(ORIGINAL, env, recipients, fields, events)).thenReturn(STAMPED);
        SignatureSealer.SealResult sealed = SignatureSealer.SealResult.plain(new byte[]{7}, "primary");
        when(primarySealer.seal(STAMPED, env)).thenReturn(Mono.just(sealed));

        StepVerifier.create(service.stampAndSeal(env, ORIGINAL, recipients, fields, events))
                .expectNext(sealed)
                .verifyComplete();
        verify(coreSealer, never()).seal(any(), any());
    }

    @Test
    void stampAndSeal_primaryFails_fallsBackToCoreSealer() {
        when(pdfService.buildStampedDocument(ORIGINAL, env, recipients, fields, events)).thenReturn(STAMPED);
        when(primarySealer.seal(STAMPED, env)).thenReturn(Mono.error(new IllegalStateException("archiving down")));
        when(primarySealer.id()).thenReturn("archiving");
        when(coreSealer.id()).thenReturn("self-signed-dev");
        SignatureSealer.SealResult fallback = SignatureSealer.SealResult.plain(new byte[]{5}, "self-signed-dev");
        when(coreSealer.seal(STAMPED, env)).thenReturn(Mono.just(fallback));

        StepVerifier.create(service.stampAndSeal(env, ORIGINAL, recipients, fields, events))
                .expectNext(fallback)
                .verifyComplete();
        verify(coreSealer).seal(eq(STAMPED), eq(env));
    }

    @Test
    void stampAndSeal_primaryIsCoreSealer_noFallback_errorPropagates() {
        SignatureServiceImpl coreOnly = service(coreSealer, coreSealer);
        when(pdfService.buildStampedDocument(ORIGINAL, env, recipients, fields, events)).thenReturn(STAMPED);
        when(coreSealer.seal(STAMPED, env)).thenReturn(Mono.error(new IllegalStateException("seal failed")));

        StepVerifier.create(coreOnly.stampAndSeal(env, ORIGINAL, recipients, fields, events))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class).hasMessage("seal failed"))
                .verify();
        verify(coreSealer).seal(STAMPED, env); // exactly once — no retry through itself
    }

    @Test
    void stampAndSeal_stampingFails_neverReachesSealer() {
        when(pdfService.buildStampedDocument(ORIGINAL, env, recipients, fields, events))
                .thenThrow(new IllegalStateException("Failed to stamp / certify PDF"));

        StepVerifier.create(service.stampAndSeal(env, ORIGINAL, recipients, fields, events))
                .expectErrorMessage("Failed to stamp / certify PDF")
                .verify();
        verify(primarySealer, never()).seal(any(), any());
        verify(coreSealer, never()).seal(any(), any());
    }

    @Test
    void defaultTenant_isFixed() {
        assertThat(SignatureServiceImpl.DEFAULT_TENANT).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(SignatureServiceImpl.SIGN_NOT_AUTHORIZED_MESSAGE).isNotBlank();
    }
}
