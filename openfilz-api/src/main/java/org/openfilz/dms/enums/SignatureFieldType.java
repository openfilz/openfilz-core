package org.openfilz.dms.enums;

/**
 * Field types a recipient can be asked to fill. Image-valued types carry a base64 PNG in
 * {@code valueImage}; every other type stores its value as text in {@code value}
 * (checkbox = "true"/"false", date = ISO-8601, select/radio = the chosen option).
 */
public enum SignatureFieldType {
    SIGNATURE, INITIALS, DATE_SIGNED, TEXT, NUMBER, EMAIL, PHONE,
    CHECKBOX, RADIO, SELECT, IMAGE, STAMP;

    public boolean isImage() {
        return this == SIGNATURE || this == INITIALS || this == IMAGE || this == STAMP;
    }

    /** Filled automatically by the server when the recipient signs (never asked in the UI). */
    public boolean isAuto() {
        return this == DATE_SIGNED;
    }
}
