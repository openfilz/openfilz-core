package org.openfilz.dms.service.signature;

import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;

/**
 * Outbound e-mail seam for e-Sign. Implementations are fire-and-forget (they must never block
 * the reactive pipeline nor propagate errors). Core provides {@code SmtpSignatureMailer} when
 * {@code spring.mail.host} is configured and {@code LoggingSignatureMailer} otherwise.
 */
public interface SignatureMailer {

    /** Invitation with the tokenized signing link. */
    void sendRequest(SignatureEnvelope envelope, SignatureRecipient recipient, String documentName, String link);

    /** Reminder (manual resend or scheduled) with a (possibly new) signing link. */
    void sendReminder(SignatureEnvelope envelope, SignatureRecipient recipient, String documentName, String link);

    /** One-time access code for EMAIL_OTP recipients. */
    void sendOtp(SignatureEnvelope envelope, SignatureRecipient recipient, String code, int validMinutes);

    /** Final sealed document to one party (initiator, signer or CC). */
    void sendCompleted(SignatureEnvelope envelope, String toEmail, String toName, String locale,
                       byte[] signedPdf, String fileName);

    /** Alert to the initiator that a recipient declined (voids the envelope). */
    void sendDeclined(SignatureEnvelope envelope, SignatureRecipient decliner);
}
