package org.openfilz.dms.enums;

/** Lifecycle of an e-Sign envelope. DRAFT → SENT → COMPLETED | DECLINED | CANCELLED | EXPIRED. */
public enum SignatureEnvelopeStatus {
    DRAFT, SENT, COMPLETED, DECLINED, CANCELLED, EXPIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == DECLINED || this == CANCELLED || this == EXPIRED;
    }
}
