/**
 * OpenFilz TypeScript SDK Quick Start
 *
 * Demonstrates the core document management operations:
 * - Folder creation, listing
 * - File upload, download, rename, move, copy, delete
 * - Favorites, metadata, dashboard, audit trail
 *
 * Prerequisites:
 *   - A running OpenFilz API instance (default: http://localhost:8081)
 *   - npm install @openfilz-sdk/typescript
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

const API_URL = process.env.OPENFILZ_API_URL || 'http://localhost:8081';

export async function runQuickStart(): Promise<void> {
  // ──── 1. Configure the SDK client ────────────────────────────────
  const config = new Configuration({
    basePath: API_URL,
    // For authenticated instances:
    // accessToken: 'your-oauth2-access-token',
  });

  const folderApi = new FolderControllerApi(config);
  const documentApi = new DocumentControllerApi(config);
  const fileApi = new FileControllerApi(config);
  const favApi = new FavoritesApi(config);
  const dashboardApi = new DashboardApi(config);
  const auditApi = new AuditControllerApi(config);

  // ──── 2. Create a folder ─────────────────────────────────────────
  const folder = await folderApi.createFolder({
    name: 'TypeScript Quick Start',
    parentId: undefined,
  });
  const folderId = folder.data.id;
  console.log(`Created folder: ${folder.data.name} (id=${folderId})`);

  // ──── 3. Upload a file ───────────────────────────────────────────
  const tempFilePath = path.join(__dirname, 'temp-quickstart.txt');
  await fsp.writeFile(tempFilePath, 'Hello from OpenFilz TypeScript SDK!');

  // Async file read — avoids blocking the event loop
  const fileBuffer = await fsp.readFile(tempFilePath);
  const uploaded = await documentApi.uploadDocument1(
    new File([fileBuffer], 'quickstart-demo.txt', { type: 'text/plain' }),
    true,       // allowDuplicateFileNames
    folderId,   // parentFolderId
    undefined,  // metadata
  );
  const fileId = uploaded.data.id!;
  console.log(`Uploaded: ${uploaded.data.name} (id=${fileId})`);

  // ──── 4. List folder contents ────────────────────────────────────
  const contents = await folderApi.listFolder(folderId, false, false);
  console.log(`Folder contains ${contents.data.length} item(s):`);
  contents.data.forEach((item) => {
    console.log(`  ${item.type}: ${item.name}`);
  });

  // ──── 5. Get document info ───────────────────────────────────────
  const info = await documentApi.getDocumentInfo(fileId, true);
  console.log(`Document: name=${info.data.name}, type=${info.data.contentType}`);

  // ──── 6. Update metadata ─────────────────────────────────────────
  await documentApi.updateDocumentMetadata(fileId, {
    metadataToUpdate: { project: 'TypeScript Demo', version: 1 },
  });
  console.log('Metadata updated');

  // ──── 7. Download the file ───────────────────────────────────────
  // Stream to a temp file instead of buffering entire response in memory
  const downloadPath = path.join(__dirname, 'temp-download.txt');
  const downloadResp = await documentApi.downloadDocument(fileId, {
    responseType: 'stream',
  });
  await pipeline(downloadResp.data as any, fs.createWriteStream(downloadPath));
  const downloadedSize = (await fsp.stat(downloadPath)).size;
  console.log(`Downloaded file to disk (${downloadedSize} bytes)`);
  await fsp.unlink(downloadPath);

  // ──── 8. Rename the file ─────────────────────────────────────────
  const renamed = await fileApi.renameFile(fileId, { newName: 'ts-renamed.txt' });
  console.log(`Renamed to: ${renamed.data.name}`);

  // ──── 9. Create target folder and copy the file ──────────────────
  const targetFolder = await folderApi.createFolder({
    name: 'TypeScript Quick Start Target',
  });
  const targetFolderId = targetFolder.data.id;

  const copies = await fileApi.copyFiles({
    documentIds: [fileId],
    targetFolderId: targetFolderId,
  });
  const copyId = copies.data[0].copyId!;
  console.log(`Copied file, new id=${copyId}`);

  // ──── 10. Move the original file ─────────────────────────────────
  await fileApi.moveFiles({
    documentIds: [fileId],
    targetFolderId: targetFolderId,
    allowDuplicateFileNames: true,
  });
  console.log('Moved file to target folder');

  // ──── 11. Toggle favorite ────────────────────────────────────────
  const isFavorite = await favApi.toggleFavorite(fileId);
  console.log(`Favorite toggled: ${isFavorite.data}`);

  // ──── 12. Dashboard statistics ───────────────────────────────────
  const stats = await dashboardApi.getDashboardStatistics();
  console.log(`Dashboard: files=${stats.data.totalFiles}, folders=${stats.data.totalFolders}`);

  // ──── 13. Audit trail ────────────────────────────────────────────
  const trail = await auditApi.getAuditTrail(fileId, 'DESC');
  console.log(`Audit trail: ${trail.data.length} entries`);

  // ──── 14. Cleanup ────────────────────────────────────────────────
  await favApi.removeFavorite(fileId);
  await fileApi.deleteFiles({ documentIds: [fileId, copyId] });
  await folderApi.deleteFolders({ documentIds: [folderId, targetFolderId] });
  console.log('Cleanup complete');

  // Remove temp file
  await fsp.unlink(tempFilePath).catch(() => {});
}

// Run if executed directly
if (require.main === module) {
  runQuickStart().catch(console.error);
}
