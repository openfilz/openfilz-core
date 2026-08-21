package org.openfilz.dms.repository;

import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface SignatureEnvelopeRepository extends ReactiveCrudRepository<SignatureEnvelope, UUID> {

    Flux<SignatureEnvelope> findByInitiatorEmailOrderByCreatedAtDesc(String initiatorEmail);

    Flux<SignatureEnvelope> findByInitiatorEmailAndStatusOrderByCreatedAtDesc(String initiatorEmail,
                                                                            SignatureEnvelopeStatus status);

    /** Sweeper: SENT envelopes past their TTL. */
    @Query("SELECT * FROM signature_envelope WHERE status = 'SENT' AND expires_at < :now")
    Flux<SignatureEnvelope> findSentPastDeadline(OffsetDateTime now);

    /** Reminders (EE scheduler): SENT envelopes with a cadence whose last reminder is older than the cadence. */
    @Query("""
           SELECT * FROM signature_envelope
           WHERE status = 'SENT' AND reminder_days IS NOT NULL AND reminder_days > 0
             AND COALESCE(last_reminded_at, sent_at, created_at) + (reminder_days * INTERVAL '1 day') < :now
           """)
    Flux<SignatureEnvelope> findDueForReminder(OffsetDateTime now);

    Flux<SignatureEnvelope> findBySourceDocId(UUID sourceDocId);
}
