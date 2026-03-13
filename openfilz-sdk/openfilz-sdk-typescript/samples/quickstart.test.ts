/**
 * Integration test for the TypeScript SDK Quick Start sample.
 *
 * Expects the OpenFilz API to be running at OPENFILZ_API_URL (default: http://localhost:18081).
 * In CI, the API is started before this test runs.
 */

import {
  Configuration,
  DocumentControllerApi,
  FolderControllerApi,
  FileControllerApi,
  FavoritesApi,
  DashboardApi,
  AuditControllerApi,
} from '@openfilz-sdk/typescript';
import * as fs from 'fs';
import * as fsp from 'fs/promises';
import * as path from 'path';
import { pipeline } from 'stream/promises';

const API_URL = process.env.OPENFILZ_API_URL || 'http://localhost:18081';

describe('TypeScript SDK Quick Start', () => {
  const config = new Configuration({ basePath: API_URL });
  const folderApi = new FolderControllerApi(config);
  const documentApi = new DocumentControllerApi(config);
  const fileApi = new FileControllerApi(config);
  const favApi = new FavoritesApi(config);
  const dashboardApi = new DashboardApi(config);
  const auditApi = new AuditControllerApi(config);

  let folderId: string;
  let targetFolderId: string;
  let fileId: string;
  let copyFileId: string;
  const tempFilePath = path.join(__dirname, 'test-upload.txt');

  beforeAll(() => {
    fs.writeFileSync(tempFilePath, 'TypeScript SDK test content');
  });

  afterAll(() => {
    if (fs.existsSync(tempFilePath)) fs.unlinkSync(tempFilePath);
  });

  it('should create a folder', async () => {
    const resp = await folderApi.createFolder({
      name: 'TS SDK Test ' + Date.now(),
    });
    expect(resp.data.id).toBeDefined();
    folderId = resp.data.id!;
  });

  it('should upload a file', async () => {
    // Async file read — avoids blocking the event loop
    const fileBuffer = await fsp.readFile(tempFilePath);
    const resp = await documentApi.uploadDocument1(
      new File([fileBuffer], 'test-upload.txt', { type: 'text/plain' }),
      true,
      folderId,
      undefined,
    );
    expect(resp.data.id).toBeDefined();
    expect(resp.data.name).toBeDefined();
    fileId = resp.data.id!;
  });

  it('should list folder contents', async () => {
    const resp = await folderApi.listFolder(folderId, false, false);
    expect(resp.data.length).toBeGreaterThan(0);
    expect(resp.data.some((item) => item.id === fileId)).toBe(true);
  });

  it('should get document info', async () => {
    const resp = await documentApi.getDocumentInfo(fileId, true);
    expect(resp.data.name).toBeDefined();
    expect(resp.data.contentType).toBe('text/plain');
  });

  it('should update metadata', async () => {
    const resp = await documentApi.updateDocumentMetadata(fileId, {
      metadataToUpdate: { project: 'TS Test' },
    });
    expect(resp.status).toBeLessThan(300);
  });

  it('should download the file', async () => {
    // Stream to a temp file instead of buffering entire response in memory
    const downloadPath = path.join(__dirname, 'temp-download.txt');
    const resp = await documentApi.downloadDocument(fileId, {
      responseType: 'stream',
    });
    await pipeline(resp.data as any, fs.createWriteStream(downloadPath));
    const stat = await fsp.stat(downloadPath);
    expect(stat.size).toBeGreaterThan(0);
    await fsp.unlink(downloadPath);
  });

  it('should rename the file', async () => {
    const resp = await fileApi.renameFile(fileId, { newName: 'ts-test-renamed.txt' });
    expect(resp.data.name).toBe('ts-test-renamed.txt');
  });

  it('should copy the file to a new folder', async () => {
    const folderResp = await folderApi.createFolder({
      name: 'TS SDK Target ' + Date.now(),
    });
    targetFolderId = folderResp.data.id!;

    const resp = await fileApi.copyFiles({
      documentIds: [fileId],
      targetFolderId: targetFolderId,
    });
    expect(resp.data.length).toBeGreaterThan(0);
    copyFileId = resp.data[0].copyId!;
  });

  it('should move the file', async () => {
    await fileApi.moveFiles({
      documentIds: [fileId],
      targetFolderId: targetFolderId,
      allowDuplicateFileNames: true,
    });
    // Verify by listing target folder
    const resp = await folderApi.listFolder(targetFolderId, true, false);
    expect(resp.data.some((item) => item.id === fileId)).toBe(true);
  });

  it('should toggle favorite', async () => {
    const resp = await favApi.toggleFavorite(fileId);
    expect(resp.data).toBe(true);
  });

  it('should get dashboard statistics', async () => {
    const resp = await dashboardApi.getDashboardStatistics();
    expect(resp.data.totalFiles).toBeGreaterThan(0);
  });

  it('should get audit trail', async () => {
    const resp = await auditApi.getAuditTrail(fileId, 'DESC');
    expect(resp.data.length).toBeGreaterThan(0);
  });

  it('should cleanup', async () => {
    await favApi.removeFavorite(fileId);
    await fileApi.deleteFiles({ documentIds: [fileId, copyFileId] });
    await folderApi.deleteFolders({ documentIds: [folderId, targetFolderId] });
  });
});
