package org.openfilz.dms.enums;

public enum Role {
    AUDITOR, // Access to Audit trail
    CONTRIBUTOR, // Access to all endpoints except the "Delete" ones
    READER, // Access only to read-only endpoints
    CLEANER // Access to all "Delete" endpoints
}
