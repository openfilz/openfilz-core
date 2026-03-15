# OpenFilz User Guide

This guide explains how to use OpenFilz through the web interface (OpenFilz Web). It is intended for end users who manage documents, folders, and files on a day-to-day basis.

---

## Table of Contents

- [What is OpenFilz?](#what-is-openfilz)
- [Core Principles](#core-principles)
- [Getting Started](#getting-started)
- [Dashboard](#dashboard)
- [My Folder (File Explorer)](#my-folder-file-explorer)
- [Uploading Files](#uploading-files)
- [Creating Folders and Documents](#creating-folders-and-documents)
- [File and Folder Operations](#file-and-folder-operations)
- [File Preview and Editing](#file-preview-and-editing)
- [Search](#search)
- [Favorites](#favorites)
- [Recycle Bin](#recycle-bin)
- [Document Properties and Metadata](#document-properties-and-metadata)
- [Audit Trail](#audit-trail)
- [Settings](#settings)
- [Roles and Permissions](#roles-and-permissions)
- [Keyboard Shortcuts](#keyboard-shortcuts)

---

## What is OpenFilz?

OpenFilz is a modern document management system (DMS) that lets you store, organize, search, and manage documents securely on your own infrastructure. Think of it as a self-hosted alternative to cloud-based file management services like Google Drive or SharePoint, with the key difference that **you retain full control over your data**.

### Key Capabilities

- **Virtual folder hierarchy** to organize documents
- **Upload and download** files of any size (with resumable uploads for large files)
- **Preview and edit** Office documents, PDFs, images, and code files directly in the browser
- **Full-text search** across millions of documents
- **Custom metadata** to tag and classify documents
- **Favorites and recycle bin** for quick access and safe deletion
- **Immutable audit trail** tracking every operation
- **Multi-language support** (English, French, German, Arabic, Spanish, Portuguese, Italian, Dutch)

---

## Core Principles

### Data Sovereignty

OpenFilz is designed to run on **your own infrastructure** — your datacenter, your private cloud, or any cloud provider of your choice. Your documents never leave your control. This makes OpenFilz suitable for organizations with strict data residency or compliance requirements.

### Storage Flexibility

Documents are stored on one of two backends, configured by your administrator:

- **Local filesystem** — files are stored directly on the server's disk
- **S3-compatible storage** (MinIO, AWS S3, etc.) — files are stored in object storage buckets, with optional bucket versioning for automatic version history

The folder hierarchy you see in the UI is a **virtual** structure stored in the database. This means operations like moving or renaming folders are instant, regardless of how many files they contain.

### Security and Compliance

- **Authentication** via your organization's identity provider (Keycloak with OIDC/OAuth2)
- **Role-based access control** determining what each user can do (read, write, delete, audit)
- **WORM mode** (Write Once, Read Many) for regulatory archives that must not be modified
- **SHA-256 checksums** for file integrity verification
- **Cryptographically chained audit logs** that cannot be tampered with

---

## Getting Started

### Logging In

Open the OpenFilz URL provided by your administrator in a web browser. If authentication is enabled, you will be redirected to your organization's login page (Keycloak). You can sign in with:

- Your username and password
- A social provider (Google, GitHub, Microsoft) if configured by your administrator

After authentication, you land on the **Dashboard**.

### Navigation

The left sidebar provides access to all main sections:

| Section | Description |
|---------|-------------|
| **Dashboard** | Storage overview and recent activity |
| **My Folder** | Main file explorer — browse, upload, and manage documents |
| **Recycle Bin** | Recover or permanently delete removed items |
| **Favorites** | Quick access to starred files and folders |
| **Settings** | Theme selection and account information |

The **header bar** contains:

- A **search bar** for finding documents across all folders
- A **language selector** (8 languages supported, including RTL for Arabic)
- Your **profile initials**

---

## Dashboard

The Dashboard provides an at-a-glance overview of your document library:

- **Storage usage** — circular indicator showing used vs. total space, with a breakdown by file type (documents, images, videos, audio, other)
- **File and folder counts** — total number of files and folders
- **Recently edited files** — the most recently modified documents with quick access to view or download them

---

## My Folder (File Explorer)

This is the primary workspace for managing your documents.

### View Modes

Toggle between two layouts using the toolbar button:

- **Grid view** — card-based layout with file thumbnails
- **List view** — sortable table with columns for name, size, type, owner, and last modified date

### Navigating Folders

- **Double-click** a folder to open it
- Use the **breadcrumb** trail at the top to navigate back to parent folders
- Click any breadcrumb segment to jump directly to that folder level

### Sorting

Sort items by any of these fields (ascending or descending):

- Name (default)
- Date modified
- Size
- Type
- Owner
- Date created

### Pagination

Control how many items appear per page: **25** (default), **50**, **70**, or **100**. Navigation buttons let you move between pages.

### Selecting Items

- Click a file or folder to select it
- **Shift+Click** for range selection
- Use the **Select All** checkbox to select all items on the current page
- The selection count displays in the toolbar

---

## Uploading Files

### Standard Upload

1. Click the **Upload Files** button in the toolbar, or **drag and drop** files onto the file explorer area
2. In the upload dialog:
   - Select one or more files
   - Optionally add **custom metadata** (key-value pairs)
   - Toggle **Allow duplicate file names** if needed
3. Click **Upload**

### Resumable Uploads (Large Files)

For large files, OpenFilz uses the **TUS protocol** for resumable uploads. If the connection drops during upload, the upload resumes from where it left off instead of restarting from scratch.

A floating **upload progress panel** shows:

- Per-file progress bars
- Pause/Resume controls
- Cancel and clear completed options
- Overall progress

### Upload Limits

Your administrator may configure:

- **Per-file size limit** — maximum size for a single file
- **User storage quota** — maximum total storage per user

If you exceed either limit, the upload is rejected with an error message.

---

## Creating Folders and Documents

### New Folder

1. Click the **New Folder** button in the toolbar
2. Enter a folder name
3. Click **Create**

### New Blank Document

1. Click the **Create Document** button
2. Choose a document type:
   - **Word** (.docx)
   - **Excel** (.xlsx)
   - **PowerPoint** (.pptx)
   - **Text** (.txt)
3. Enter a name (a default is suggested)
4. Click **Create**

The document is created and ready for editing (via OnlyOffice if enabled, or in the browser viewer).

---

## File and Folder Operations

Select one or more items, then use the toolbar or context menu:

| Operation | Description |
|-----------|-------------|
| **Rename** | Change the name of a file or folder |
| **Move** | Move items to a different folder (folder tree dialog) |
| **Copy** | Duplicate items to a target folder |
| **Download** | Download a single file, or multiple files/folders as a ZIP archive |
| **Delete** | Move items to the recycle bin (soft delete) |
| **Add to Favorites** | Star an item for quick access |
| **View Properties** | Open the metadata and audit panel |

All operations are tracked in the audit trail.

---

## File Preview and Editing

Double-click a file or click **Open** to preview it. OpenFilz supports in-browser viewing for many file types:

### Supported File Types

| Type | Viewer | Features |
|------|--------|----------|
| **PDF** | PDF.js | Page navigation, zoom, rotate, print, fullscreen |
| **Images** (PNG, JPG, GIF, WebP, SVG, BMP, TIFF) | Built-in | Zoom, rotate, fullscreen |
| **Office** (Word, Excel, PowerPoint) | OnlyOffice (if enabled) or HTML rendering | Full editing or read-only |
| **Text, Code** (JSON, XML, HTML, CSS, JS, etc.) | Monaco editor | Syntax highlighting, line numbers |
| **Unsupported types** | — | Download prompt |

### File Size Limit for Preview

Files larger than **10 MB** display a warning and offer a download button instead. OnlyOffice documents are exempt from this limit.

### OnlyOffice Integration (if enabled)

When OnlyOffice is available, you can **edit** Word, Excel, and PowerPoint documents directly in the browser with a full office-like interface, including real-time collaborative editing.

---

## Search

### Quick Search

Type in the **search bar** in the header. Results appear as live suggestions as you type:

- Click a result to navigate to it
- Use the download icon to download directly from the suggestion list

### Full Search Results

Press **Enter** or click the search icon to open the full search results page with advanced filtering:

| Filter | Options |
|--------|---------|
| **File type** | All, Folders, Files |
| **Document type** | PDFs, Images, Documents, Spreadsheets |
| **Date modified** | Any, Today, Last 7 days, Last 30 days |
| **Owner** | Filter by creator |
| **Metadata** | Add custom key-value filters |

If **full-text search** is enabled (OpenSearch), the search also looks inside document content, not just file names.

---

## Favorites

Star any file or folder by clicking the **star icon** on it. Access all your favorites from the **Favorites** page in the sidebar.

Favorites are per-user — other users do not see your starred items.

From the Favorites page, you can:

- Open, download, or perform operations on favorited items
- Remove items from favorites by clicking the star icon again

---

## Recycle Bin

When you delete a file or folder, it moves to the **Recycle Bin** instead of being permanently destroyed (if soft delete is enabled by your administrator).

### Recycle Bin Actions

| Action | Description |
|--------|-------------|
| **Restore** | Recover the item to its original location |
| **Delete Forever** | Permanently remove the item (irreversible, requires confirmation) |
| **Empty Bin** | Permanently delete all items in the recycle bin |

### Auto-Cleanup

Items in the recycle bin are automatically purged after a configurable period (default: **30 days**). The remaining time is displayed on the recycle bin page.

---

## Document Properties and Metadata

Select a file and click **View Properties** to open the metadata panel with two tabs:

### General Tab

- **Name**, **Type**, **Size**
- **SHA-256 hash** (if checksums are enabled) — copyable for integrity verification
- **Custom metadata** — key-value pairs attached to the document
  - Click **Edit** to add, modify, or remove metadata entries
  - Keys must be alphanumeric (underscores and hyphens allowed)
  - Keys must be unique within a document

### Audit Tab

Displays the complete history of operations on the document (see [Audit Trail](#audit-trail) below).

---

## Audit Trail

Every operation on every document is recorded in an immutable audit trail. The audit log for a document is accessible from the **Properties panel > Audit tab**.

### Tracked Operations

| Action | Description |
|--------|-------------|
| Upload | File uploaded |
| Download | File downloaded |
| Create Folder | Folder created |
| Rename | File or folder renamed |
| Move | File or folder moved |
| Copy | File or folder copied |
| Delete | File or folder deleted |
| Restore | Item restored from recycle bin |
| Permanent Delete | Item permanently destroyed |
| Replace Content | File content replaced |
| Update Metadata | Metadata modified |
| Delete Metadata | Metadata keys removed |
| Share | Document sharing changed |
| Comment | Comment added, edited, or deleted |
| Empty Recycle Bin | Recycle bin emptied |

Each audit entry records:

- **Who** — the user who performed the action
- **What** — the action type
- **When** — the timestamp (displayed as relative time: "2h ago", "3d ago")

The audit chain uses **SHA-256 cryptographic hashing** to ensure logs cannot be altered or deleted.

---

## Settings

Access Settings from the sidebar or header menu.

### Appearance

Choose from **10 built-in themes**:

Light, Dark, Ocean, Forest, Sunset, Lavender, Rose, Midnight, Slate, Copper

Your theme preference is saved in your browser and applies immediately.

### Quotas

If configured by your administrator, the Settings page shows:

- **File upload limit** — maximum size per file
- **Total storage limit** — your account's storage quota

---

## Roles and Permissions

Your administrator assigns roles that determine what you can do. OpenFilz defines four built-in roles:

| Role | Permissions |
|------|-------------|
| **READER** | View and download documents, browse folders, use search |
| **CONTRIBUTOR** | Everything a READER can do, plus upload, create, rename, move, copy, and update documents |
| **CLEANER** | Delete documents and folders, empty recycle bin |
| **AUDITOR** | Access audit trail and compliance reports |

A user can have **multiple roles**. For example, a typical user might have both `CONTRIBUTOR` and `CLEANER` roles for full read-write-delete access.

### WORM Mode

If your administrator enables **WORM mode** (Write Once, Read Many), documents become read-only after upload. No modifications or deletions are allowed. This is used for regulatory compliance (SEC 17a-4, FINRA, HIPAA, etc.).

---

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Shift + ?` | Show keyboard shortcuts help |
| `Escape` | Close dialogs or cancel actions |
| `Ctrl + S` | Save (when editing text documents) |

---

## Language Support

OpenFilz is available in 8 languages. Switch languages using the language selector in the header:

- English (default)
- French (Francais)
- German (Deutsch)
- Arabic (with RTL layout support)
- Spanish (Espanol)
- Portuguese (Portugues)
- Italian (Italiano)
- Dutch (Nederlands)

---

## Tips and Best Practices

1. **Use metadata** to classify documents beyond folder structure — metadata is searchable and filterable
2. **Star frequently used documents** as favorites for quick access
3. **Use the recycle bin** as a safety net — deleted items can be restored before the auto-cleanup period expires
4. **For large files**, the resumable upload ensures your upload survives network interruptions
5. **Check the audit trail** to track who modified a document and when
