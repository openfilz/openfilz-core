export enum DocumentType {FILE = "FILE", FOLDER = "FOLDER"}

export interface ElementInfo {
  id: string;
  name: string;
  type: DocumentType;
}

export interface ListFolderAndCountResponse {
  listFolder: ElementInfo[];
  count: number;
}

export interface DocumentInfo {
  type: DocumentType;
  name: string;
  parentId?: string;
  metadata?: { [key: string]: any };
  size?: number;
}

export interface CreateFolderRequest {
  name: string;
  parentId?: string;
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
}

export interface MultipleUploadFileParameter {
  filename: string;
  fileAttributes: MultipleUploadFileParameterAttributes;
}

export interface MultipleUploadFileParameterAttributes {
  parentFolderId?: string;
  metadata?: { [key: string]: any };
}

export class Root implements ElementInfo {

  public static INSTANCE = new Root();

  id = "0"
  name = "Root"
  type = DocumentType.FOLDER
}