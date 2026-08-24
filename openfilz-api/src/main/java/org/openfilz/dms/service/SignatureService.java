package org.openfilz.dms.service;

import org.openfilz.dms.dto.signature.ApplySignatureRequest;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.DeclineSignatureRequest;
import org.openfilz.dms.dto.signature.PublicSignatureView;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureEventDTO;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * e-Sign envelope lifecycle. Invariant violations surface as
 * {@link org.springframework.web.server.ResponseStatusException} (404 missing, 403 wrong
 * caller / OTP not verified, 409 illegal transition, 410 expired, 422 bad field values).
 */
public interface SignatureService {

    /** Identity of the authenticated initiator. {@code id} is derived from the JWT subject. */
    record Actor(UUID id, String email) {
        public static Actor of(String subject, String email) {
            UUID id;
            try {
                id = subject == null ? null : UUID.fromString(subject);
            } catch (IllegalArgumentException e) {
                id = null;
            }
            String mail = email == null ? null : email.toLowerCase();
            if (id == null) {
                id = UUID.nameUUIDFromBytes(("openfilz:" + mail).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return new Actor(id, mail);
        }
    }

    /** A freshly minted signing link for one recipient (the raw token is never persisted). */
    record SigningLink(UUID recipientId, String rawToken, String url) {}

    // ── Initiator (authenticated) ───────────────────────────────────────
    Mono<SignatureEnvelopeDTO> create(CreateSignatureEnvelopeRequest req, Actor actor);

    Mono<SignatureEnvelopeDTO> send(UUID envelopeId, String initiatorEmail);

    Flux<SignatureEnvelopeDTO> listSent(String initiatorEmail, SignatureEnvelopeStatus status);

    /** Envelopes with a still-actionable recipient row for this user (matched by email). */
    Flux<SignatureEnvelopeDTO> listToSign(String userEmail);

    Mono<SignatureEnvelopeDTO> get(UUID envelopeId, String initiatorEmail);

    Flux<SignatureEventDTO> events(UUID envelopeId, String initiatorEmail);

    Mono<SignatureEnvelopeDTO> cancel(UUID envelopeId, String initiatorEmail);

    /** New token for the recipient (the previous one is revoked) + reminder email. */
    Mono<SignatureEnvelopeDTO> resend(UUID envelopeId, UUID recipientId, String initiatorEmail);

    /** Mint a new signing link without sending any email (EE embedded signing). Revokes the previous token. */
    Mono<SigningLink> rotateToken(UUID envelopeId, UUID recipientId, String initiatorEmail);

    Mono<Resource> loadSignedDocument(UUID envelopeId, String initiatorEmail);

    // ── Public (signing token) ──────────────────────────────────────────
    Mono<PublicSignatureView> getByToken(String rawToken);

    Mono<Resource> loadDocumentByToken(String rawToken);

    Mono<PublicSignatureView> recordView(String rawToken, String ip, String userAgent);

    Mono<Void> requestOtp(String rawToken);

    Mono<PublicSignatureView> verifyOtp(String rawToken, String code, String ip);

    Mono<PublicSignatureView> applySignature(String rawToken, ApplySignatureRequest req, String ip, String userAgent);

    Mono<PublicSignatureView> decline(String rawToken, DeclineSignatureRequest req, String ip);

    // ── Schedulers ──────────────────────────────────────────────────────
    /** SENT → EXPIRED for envelopes past their deadline. Returns the number of envelopes expired. */
    Mono<Integer> sweepExpired();

    /** Reminder emails for SENT envelopes whose cadence elapsed. Returns the number of recipients reminded. */
    Mono<Integer> sendDueReminders();
}
