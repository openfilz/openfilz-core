import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import {filter, map, Observable} from 'rxjs';
import { Apollo, gql } from 'apollo-angular';
import {
  ElementInfo,
  DocumentInfo,
  CreateFolderRequest,
  FolderResponse,
  RenameRequest,
  MoveRequest,
  CopyRequest,
  DeleteRequest,
  UploadResponse,
  SearchByMetadataRequest,
  MultipleUploadFileParameter,
  MultipleUploadFileParameterAttributes,
  ListFolderAndCountResponse
} from '../models/document.models';
import {environment} from "../../environments/environment";

const LIST_FOLDER_QUERY = gql`
  query listFolder($request: ListFolderRequest!) {
    listFolder(request: $request) {
      id
      type
      contentType
      name
      size
      createdAt
      updatedAt
      createdBy
      updatedBy
    }
  }
`;

const LIST_FOLDER_AND_COUNT_QUERY = gql`
  query listFolderAndCount($request1: ListFolderRequest!, $request2: ListFolderRequest) {
    listFolder(request: $request1) {
      id
      type
      contentType
      name
      size
      createdAt
      updatedAt
      createdBy
      updatedBy
    }
    count(request: $request2)
  }
`;

@Injectable({
  providedIn: 'root'
})
export class DocumentApiService {
  private readonly baseUrl = environment.apiURL;
  private readonly authToken = localStorage.getItem('token'); // In real app, get from auth service

  constructor(private http: HttpClient, private apollo: Apollo) {}

  private getHeaders(): HttpHeaders {
    return new HttpHeaders({
      'Authorization': `Bearer ${this.authToken}`,
      'Content-Type': 'application/json'
    });
  }

  private getMultipartHeaders(): HttpHeaders {
    return new HttpHeaders({
      'Authorization': `Bearer ${this.authToken}`,
      'Accept': '*/*'
      //'Content-Type': 'multipart/form-data'
    });
  }



  // Folder operations
  listFolder(folderId?: string, page: number = 1, pageSize: number = 50): Observable<ElementInfo[]> {
    //console.log(`listFolder ${folderId} - page ${page}`);
    const request = {
      id: folderId,
      pageInfo: {
        pageNumber: page,
        pageSize: pageSize
      }
    };

    return this.apollo.watchQuery<any>({
      fetchPolicy: 'no-cache',
      query: LIST_FOLDER_QUERY,
      variables: { request }
    }).valueChanges.pipe(
      map(result => result.data.listFolder)
    );
  }

  listFolderAndCount(folderId?: string, page: number = 1, pageSize: number = 50): Observable<ListFolderAndCountResponse> {
    const request1 = {
      id: folderId,
      pageInfo: {
        pageNumber: page,
        pageSize: pageSize
      }
    };

    const request2 = {
      id: folderId
    };

    return this.apollo.watchQuery<any>({
      fetchPolicy: 'no-cache',
      query: LIST_FOLDER_AND_COUNT_QUERY,
      variables: { request1, request2 }
    }).valueChanges.pipe(
      map(result => {
        return {
          listFolder: result.data.listFolder,
          count: result.data.count
        };
      })
    );
    
  }

  createFolder(request: CreateFolderRequest): Observable<FolderResponse> {
    return this.http.post<FolderResponse>(`${this.baseUrl}/folders`, request, {
      headers: this.getHeaders()
    });
  }

  renameFolder(folderId: string, request: RenameRequest): Observable<ElementInfo> {
    return this.http.put<ElementInfo>(`${this.baseUrl}/folders/${folderId}/rename`, request, {
      headers: this.getHeaders()
    });
  }

  moveFolders(request: MoveRequest): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/folders/move`, request, {
      headers: this.getHeaders()
    });
  }

  copyFolders(request: CopyRequest): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/folders/copy`, request, {
      headers: this.getHeaders()
    });
  }

  deleteFolders(request: DeleteRequest): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/folders`, {
      headers: this.getHeaders(),
      body: request
    });
  }

  // File operations
  renameFile(fileId: string, request: RenameRequest): Observable<ElementInfo> {
    return this.http.put<ElementInfo>(`${this.baseUrl}/files/${fileId}/rename`, request, {
      headers: this.getHeaders()
    });
  }

  moveFiles(request: MoveRequest): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/files/move`, request, {
      headers: this.getHeaders()
    });
  }

  copyFiles(request: CopyRequest): Observable<any[]> {
    return this.http.post<any[]>(`${this.baseUrl}/files/copy`, request, {
      headers: this.getHeaders()
    });
  }

  deleteFiles(request: DeleteRequest): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/files`, {
      headers: this.getHeaders(),
      body: request
    });
  }

  // Document operations
  getDocumentInfo(documentId: string, withMetadata?: boolean): Observable<DocumentInfo> {
    let params = new HttpParams();
    if (withMetadata !== undefined) params = params.set('withMetadata', withMetadata.toString());

    return this.http.get<DocumentInfo>(`${this.baseUrl}/documents/${documentId}/info`, {
      headers: this.getHeaders(),
      params
    });
  }

  downloadDocument(documentId: string): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/documents/${documentId}/download`, {
      headers: this.getHeaders(),
      responseType: 'blob'
    });
  }

  downloadMultipleDocuments(documentIds: string[]): Observable<Blob> {
    return this.http.post(`${this.baseUrl}/documents/download-multiple`, documentIds, {
      headers: this.getHeaders(),
      responseType: 'blob'
    });
  }

  uploadDocument(file: File, parentFolderId?: string, metadata?: string, allowDuplicateFileNames?: boolean): Observable<UploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    if (parentFolderId) formData.append('parentFolderId', parentFolderId);
    if (metadata) formData.append('metadata', metadata);

    let params = new HttpParams();
    if (allowDuplicateFileNames !== undefined) {
      params = params.set('allowDuplicateFileNames', allowDuplicateFileNames.toString());
    }

    return this.http.post<UploadResponse>(`${this.baseUrl}/documents/upload`, formData, {
      headers: this.getMultipartHeaders(),
      params
    });
  }

  uploadMultipleDocuments(files: File[], parentFolderId?: string, allowDuplicateFileNames?: boolean): Observable<UploadResponse> {
    const formData = new FormData();
    const parametersByFilename: MultipleUploadFileParameter[] = [];
    files.forEach(file => {
      formData.append('file', file, file.name);
      parametersByFilename.push({
        filename: file.name,
        fileAttributes: {
          parentFolderId: parentFolderId
        }
      });
    });
    if (parentFolderId) {
      formData.append('parametersByFilename', new Blob([JSON.stringify(parametersByFilename)], {type: 'application/json'}));
    }


    let params = new HttpParams();
    if (allowDuplicateFileNames !== undefined) {
      params = params.set('allowDuplicateFileNames', allowDuplicateFileNames.toString());
    }

    return this.http.post<UploadResponse>(`${this.baseUrl}/documents/upload-multiple`, formData, {
      headers: this.getMultipartHeaders(),
      params
    });
  }

  searchDocumentIdsByMetadata(request: SearchByMetadataRequest): Observable<string[]> {
    return this.http.post<string[]>(`${this.baseUrl}/documents/search/ids-by-metadata`, request, {
      headers: this.getHeaders()
    });
  }
}