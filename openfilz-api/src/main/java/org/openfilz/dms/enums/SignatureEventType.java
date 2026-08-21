package org.openfilz.dms.enums;

/** Append-only events rendered into the Certificate of Completion. */
public enum SignatureEventType {
    ENVELOPE_CREATED,
    ENVELOPE_SENT,
    RECIPIENT_VIEWED,
    RECIPIENT_OTP_VERIFIED,
    RECIPIENT_SIGNED,
    RECIPIENT_DECLINED,
    RECIPIENT_REMINDED,
    RECIPIENT_LINK_RESENT,
    ENVELOPE_COMPLETED,
    ENVELOPE_CANCELLED,
    ENVELOPE_EXPIRED
}
