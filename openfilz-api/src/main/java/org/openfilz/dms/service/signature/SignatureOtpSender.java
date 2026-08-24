package org.openfilz.dms.service.signature;

import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureAuthMethod;
import reactor.core.publisher.Mono;

/**
 * Delivers a one-time code to a recipient. Core supports {@link SignatureAuthMethod#EMAIL_OTP}
 * through the {@link SignatureMailer}; the enterprise layer adds {@code SMS_OTP}.
 */
public interface SignatureOtpSender {

    boolean supports(SignatureAuthMethod method);

    Mono<Void> send(SignatureEnvelope envelope, SignatureRecipient recipient, String code, int validMinutes);
}
