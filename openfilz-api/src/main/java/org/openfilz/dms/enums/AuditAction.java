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
    REPLACE_DOCUMENT_METADATA,
    UPDATE_DOCUMENT_METADATA,
    DOWNLOAD_DOCUMENT,
    DELETE_DOCUMENT_METADATA,
    SHARE_DOCUMENTS,
    SHARE_DOCUMENT_CREATE,
    SHARE_DOCUMENT_UPDATE,
    SHARE_DOCUMENT_DELETE,
    // Recycle bin actions
    RESTORE_FILE, // Restore file from recycle bin
    RESTORE_FOLDER, // Restore folder from recycle bin
    PERMANENT_DELETE_FILE, // Permanently delete file from recycle bin
    PERMANENT_DELETE_FOLDER, // Permanently delete folder from recycle bin
    COMMENT_CREATE, COMMENT_UPDATE, COMMENT_DELETE, EMPTY_RECYCLE_BIN // Empty entire recycle bin
}
