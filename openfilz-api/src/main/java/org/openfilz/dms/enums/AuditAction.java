package org.openfilz.dms.enums;

public enum AuditAction {
    COPY_FILE,
    COPY_FILE_CHILD,
    RENAME_FILE,
    RENAME_FOLDER,
    COPY_FOLDER,
    DELETE_FILE, // Soft delete (move to recycle bin)
    DELETE_FILE_CHILD, // Soft delete child file
    DELETE_FOLDER, // Soft delete folder
    CREATE_FOLDER,
    MOVE_FILE,
    MOVE_FOLDER,
    UPLOAD_DOCUMENT,
    REPLACE_DOCUMENT_CONTENT,
    RESTORE_DOCUMENT_VERSION, // Restore a previous version of a versioned file (history-preserving)
    REPLACE_DOCUMENT_METADATA,
    UPDATE_DOCUMENT_METADATA,
    DOWNLOAD_DOCUMENT,
    DELETE_DOCUMENT_METADATA,
    // Recycle bin actions
    RESTORE_FILE, // Restore file from recycle bin
    RESTORE_FOLDER, // Restore folder from recycle bin
    PERMANENT_DELETE_FILE, // Permanently delete file from recycle bin
    PERMANENT_DELETE_FOLDER, // Permanently delete folder from recycle bin
    EMPTY_RECYCLE_BIN, // Empty entire recycle bin
    // e-Sign (electronic signature) actions
    SIGNATURE_ENVELOPE_CREATED,
    SIGNATURE_ENVELOPE_SENT,
    SIGNATURE_DOCUMENT_SIGNED,
    SIGNATURE_ENVELOPE_COMPLETED,
    SIGNATURE_ENVELOPE_DECLINED,
    SIGNATURE_ENVELOPE_CANCELLED,
    SIGNATURE_ENVELOPE_EXPIRED,
    SIGNATURE_REMINDER_SENT,
    SIGNATURE_TEMPLATE_CREATED,
    SIGNATURE_TEMPLATE_DELETED,
    // PDF tools: a document produced or replaced by merge / split / organize / rotate (details carry the provenance)
    PDF_TRANSFORM,
    // Workflows (statuses + transitions + tasks) — see docs/workflows.md
    WORKFLOW_STARTED,
    WORKFLOW_TRANSITIONED,
    WORKFLOW_COMPLETED,
    WORKFLOW_CANCELLED,
    WORKFLOW_TASK_REASSIGNED,
    WORKFLOW_ACTION_FAILED,
    WORKFLOW_DEFINITION_CREATED,
    WORKFLOW_DEFINITION_UPDATED,
    WORKFLOW_DEFINITION_DELETED,
    // Audit chain actions
    CHAIN_GENESIS // Marks the start of the hash chain
}
