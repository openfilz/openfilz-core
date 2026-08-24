package org.openfilz.dms.service.signature.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.openfilz.dms.service.signature.SignatureOtpSender;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Delivers EMAIL_OTP codes through the configured {@link SignatureMailer}. */
@Service
@RequiredArgsConstructor
public class EmailSignatureOtpSender implements SignatureOtpSender {

    private final SignatureMailer mailer;

    @Override
    public boolean supports(SignatureAuthMethod method) {
        return method == SignatureAuthMethod.EMAIL_OTP;
    }

    @Override
    public Mono<Void> send(SignatureEnvelope envelope, SignatureRecipient recipient, String code, int validMinutes) {
        return Mono.fromRunnable(() -> mailer.sendOtp(envelope, recipient, code, validMinutes));
    }
}
