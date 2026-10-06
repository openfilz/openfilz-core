package org.openfilz.dms.repository;

import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureRecipientStatus;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.UUID;

public interface SignatureRecipientRepository extends ReactiveCrudRepository<SignatureRecipient, UUID> {

    Flux<SignatureRecipient> findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(UUID envelopeId);

    /** Every recipient of a page of envelopes, in one statement — see {@code SignatureServiceImpl.loadDtos}. */
    Flux<SignatureRecipient> findByEnvelopeIdInOrderByOrderIndexAscSortOrderAscIdAsc(Collection<UUID> envelopeIds);

    /** Token lookup: the only authenticator for tokenized links (revoked tokens are refused by the service). */
    Mono<SignatureRecipient> findByTokenHash(String tokenHash);

    /**
     * Reserves one OTP verification attempt atomically: the increment and the cap check are a
     * single conditional UPDATE, so parallel guesses can never exceed {@code max} together —
     * each of them either gets a slot (1 row) or is refused (0 rows). Runs in its own
     * statement, outside the caller's transaction, so a wrong guess keeps its slot consumed.
     */
    @Modifying
    @Query("""
           UPDATE signature_recipient
              SET otp_attempts = otp_attempts + 1
            WHERE id = :id AND otp_hash IS NOT NULL AND otp_attempts < :max
           """)
    Mono<Integer> reserveOtpAttempt(UUID id, int max);

    /**
     * Issues a new one-time code under the request throttle in one conditional UPDATE: no code
     * is written when the lifetime cap ({@code maxRequests}) is reached or when the previous
     * code was issued after {@code notBefore} (cooldown). 0 rows = refused.
     */
    @Modifying
    @Query("""
           UPDATE signature_recipient
              SET otp_hash = :otpHash, otp_expires_at = :expiresAt, otp_attempts = 0, otp_verified_at = NULL,
                  otp_requested_at = :now, otp_request_count = otp_request_count + 1
            WHERE id = :id AND otp_request_count < :maxRequests
              AND (otp_requested_at IS NULL OR otp_requested_at <= :notBefore)
           """)
    Mono<Integer> issueOtp(UUID id, String otpHash, OffsetDateTime expiresAt, OffsetDateTime now,
                           OffsetDateTime notBefore, int maxRequests);

    /** Kills every signing link of an envelope — called when it reaches a terminal state. */
    @Modifying
    @Query("UPDATE signature_recipient SET token_revoked = TRUE WHERE envelope_id = :envelopeId")
    Mono<Integer> revokeTokens(UUID envelopeId);

    /** "Waiting for my signature" — matched by email so it works for internal and external users alike. */
    Flux<SignatureRecipient> findByRecipientEmailAndStatusInOrderByIdDesc(
            String recipientEmail, Collection<SignatureRecipientStatus> statuses);
}
