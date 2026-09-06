package org.openfilz.dms.enums;

public enum Role {
    AUDITOR, // Access to Audit trail
    CONTRIBUTOR, // Access to all endpoints except the "Delete" ones
    READER, // Access only to read-only endpoints
    CLEANER, // Access to all "Delete" endpoints
    SIGN_REQUESTER, // May initiate e-Sign requests (envelopes, templates) — enforced only when openfilz.signature.require-requester-role=true
    WORKFLOW_DESIGNER // May create / edit workflow definitions — enforced only when openfilz.workflows.require-designer-role=true
}
