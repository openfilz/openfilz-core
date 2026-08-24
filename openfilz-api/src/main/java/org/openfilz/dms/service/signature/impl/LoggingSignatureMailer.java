package org.openfilz.dms.service.signature.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.service.signature.SignatureMailer;

/** Used when no SMTP host is configured: logs what would have been sent (dev / evaluation only). */
@Slf4j
public class LoggingSignatureMailer implements SignatureMailer {

    @Override
    public void sendRequest(SignatureEnvelope env, SignatureRecipient r, String documentName, String link) {
        log.warn("[e-sign][no-smtp] signing request for envelope {} to {} — link: {}", env.getId(), r.getRecipientEmail(), link);
    }

    @Override
    public void sendReminder(SignatureEnvelope env, SignatureRecipient r, String documentName, String link) {
        log.warn("[e-sign][no-smtp] reminder for envelope {} to {} — link: {}", env.getId(), r.getRecipientEmail(), link);
    }

    @Override
    public void sendOtp(SignatureEnvelope env, SignatureRecipient r, String code, int validMinutes) {
        log.warn("[e-sign][no-smtp] OTP for envelope {} to {} — code: {} ({} min)", env.getId(), r.getRecipientEmail(), code, validMinutes);
    }

    @Override
    public void sendCompleted(SignatureEnvelope env, String toEmail, String toName, String locale, byte[] signedPdf, String fileName) {
        log.warn("[e-sign][no-smtp] completed envelope {} — would send {} ({} bytes) to {}", env.getId(), fileName,
                signedPdf == null ? 0 : signedPdf.length, toEmail);
    }

    @Override
    public void sendDeclined(SignatureEnvelope env, SignatureRecipient decliner) {
        log.warn("[e-sign][no-smtp] envelope {} declined by {} — would notify {}", env.getId(), decliner.getRecipientEmail(),
                env.getInitiatorEmail());
    }
}
