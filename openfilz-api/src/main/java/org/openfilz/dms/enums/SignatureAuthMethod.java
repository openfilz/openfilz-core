package org.openfilz.dms.enums;

/** Extra authentication a recipient must pass before signing. SMS_OTP needs an EE {@code SmsSender}. */
public enum SignatureAuthMethod {
    NONE, EMAIL_OTP, SMS_OTP
}
