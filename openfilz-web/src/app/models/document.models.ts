export enum DocumentType {FILE = "FILE", FOLDER = "FOLDER"}

export interface Suggestion {
    id: string;
    s: string;
    ext?: string;
}

export interface ElementInfo {
  id: string;
  name: string;
  type: DocumentType;
  contentType?: string;
  favorite?: boolean;
}

export interface ListFolderAndCountResponse {
  listFolder: ElementInfo[];
  count: number;
}

export interface DocumentInfo {
  type: DocumentType;
  contentType?: string;
  name: string;
  parentId?: string;
  metadata?: { [key: string]: any };
  size?: number;
}

export interface CreateFolderRequest {
  name: string;
  parentId?: string;
}

export interface FilterInput {
  field: string;
  value: string;
}


export interface FolderResponse {
  id: string;
  name: string;
  parentId?: string;
}

export interface RenameRequest {
  newName: string;
  }

export interface MoveRequest {
  documentIds: string[];
  targetFolderId?: string;
  allowDuplicateFileNames?: boolean;
}

export interface CopyRequest {
  documentIds: string[];
  targetFolderId?: string;
  allowDuplicateFileNames?: boolean;
}

export interface DeleteRequest {
  documentIds: string[];
}

export interface UploadResponse {
  id: string;
  name: string;
  contentType: string;
  size: number;
}

export interface SearchByMetadataRequest {
  name?: string;
  type?: DocumentType;
  parentFolderId?: string;
  rootOnly?: boolean;
  metadataCriteria?: { [key: string]: any };
}

export interface FileItem extends ElementInfo {
  selected?: boolean;
  size?: number;
  modifiedDate?: Date;
  icon?: string;
  favorite?: boolean;
}

export interface MultipleUploadFileParameter {
  filename: string;
  fileAttributes: MultipleUploadFileParameterAttributes;
}

export interface MultipleUploadFileParameterAttributes {
  parentFolderId?: string;
  metadata?: { [key: string]: any };
}

export interface DocumentSearchInfo {
    id: string;
    name: string;
    extension: string;
    size: number;
    parentId: string;
    createdAt: string;
    updatedAt: string;
    createdBy: string;
    updatedBy: string;
}

export interface DocumentSearchResult {
    totalHits: number;
    documents: DocumentSearchInfo[];
}

export class Root implements ElementInfo {

  public static INSTANCE = new Root();

  id = "0"
  name = "Root"
  type = DocumentType.FOLDER
}

// Dashboard models
export interface FileTypeStats {
  type: string;
  count?: number;
  totalSize?: number;
}

export interface StorageBreakdown {
  totalStorageUsed: number;
  totalStorageAvailable?: number;
  fileTypeBreakdown: FileTypeStats[];
}

export interface DashboardStatistics {
  totalFiles: number;
  totalFolders: number;
  storage: StorageBreakdown;
  fileTypeCounts: FileTypeStats[];
}

export interface RecentFileInfo extends ElementInfo {
  size?: number;
  contentType?: string;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
  updatedBy?: string;
}

// Audit models
export type AuditAction =
  | 'COPY_FILE'
  | 'COPY_FILE_CHILD'
  | 'RENAME_FILE'
  | 'RENAME_FOLDER'
  | 'COPY_FOLDER'
  | 'DELETE_FILE'
  | 'DELETE_FILE_CHILD'
  | 'DELETE_FOLDER'
  | 'CREATE_FOLDER'
  | 'MOVE_FILE'
  | 'MOVE_FOLDER'
  | 'UPLOAD_DOCUMENT'
  | 'REPLACE_DOCUMENT_CONTENT'
  | 'REPLACE_DOCUMENT_METADATA'
  | 'UPDATE_DOCUMENT_METADATA'
  | 'DOWNLOAD_DOCUMENT'
  | 'DELETE_DOCUMENT_METADATA'
  | 'SHARE_DOCUMENTS'
  | 'RESTORE_FILE'
  | 'RESTORE_FOLDER'
  | 'PERMANENT_DELETE_FILE'
  | 'PERMANENT_DELETE_FOLDER'
  | 'EMPTY_RECYCLE_BIN';

export interface AuditLogDetails {
  type: string;
  [key: string]: any;
}

export interface AuditLog {
  id: string;
  timestamp: string;
  username: string;
  action: AuditAction;
  resourceType: DocumentType;
  details?: AuditLogDetails;
}

export interface PageCriteria {
  pageNumber: number;
  pageSize: number;
  sortBy?: string;
  sortOrder?: 'ASC' | 'DESC';
}

export interface ListFolderRequest {
  id?: string;
  type?: DocumentType;
  contentType?: string;
  name?: string;
  nameLike?: string;
  metadata?: { [key: string]: any };
  size?: number;
  createdAtAfter?: string;
  createdAtBefore?: string;
  updatedAtAfter?: string;
  updatedAtBefore?: string;
  createdBy?: string;
  updatedBy?: string;
  favorite?: boolean;
  active?: boolean;
  pageInfo?: PageCriteria;
}

export interface SearchFilters {
  type?: DocumentType;
  dateModified?: string;
  owner?: string;
  fileType?: string;
  metadata?: { key: string; value: string }[];
}

// Navigation interfaces for search suggestion navigation
export interface AncestorInfo {
  id: string;
  name: string;
  type: string;
}

export interface DocumentPosition {
  documentId: string;
  parentId: string | null;
  position: number;
  totalItems: number;
}