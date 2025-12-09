# Metadata Panel - Code Highlights

## Key Implementation Snippets

### 1. Panel Component - Animation Configuration

```typescript
// metadata-panel.component.ts
import { trigger, state, style, transition, animate } from '@angular/animations';

@Component({
  animations: [
    trigger('slideInOut', [
      state('in', style({
        transform: 'translateX(0)',
        opacity: 1
      })),
      state('out', style({
        transform: 'translateX(100%)',
        opacity: 0
      })),
      transition('in => out', animate('300ms ease-in-out')),
      transition('out => in', animate('300ms ease-in-out'))
    ])
  ]
})
export class MetadataPanelComponent implements OnInit, OnChanges {
  @Input() documentId?: string;
  @Input() isOpen: boolean = false;
  @Output() closePanel = new EventEmitter<void>();
  @Output() metadataSaved = new EventEmitter<void>();

  get animationState(): string {
    return this.isOpen ? 'in' : 'out';
  }
}
```

### 2. Panel Styling - Right-Side Fixed Positioning

```css
/* metadata-panel.component.css */

/* Overlay for dimming background */
.metadata-panel-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.3);
  z-index: 999;
  transition: opacity 300ms ease-in-out;
}

/* Right-side collapsible panel */
.metadata-panel {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 420px;
  max-width: 90vw;
  background-color: var(--bg-primary);
  box-shadow: -4px 0 20px rgba(0, 0, 0, 0.15);
  z-index: 1000;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: transform 300ms ease-in-out;
}

/* Gradient header matching theme */
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  background: linear-gradient(135deg, var(--primary) 0%, var(--primary-dark) 100%);
  color: white;
  box-shadow: var(--shadow-sm);
}
```

### 3. Upload Dialog Simplification

```typescript
// upload-dialog.component.ts - BEFORE
export interface UploadDialogResult {
  files: File[];
  metadata?: { [key: string]: any };  // ❌ Removed
  allowDuplicates: boolean;
}

@Component({
  imports: [
    MatExpansionModule,              // ❌ Removed
    MetadataEditorComponent          // ❌ Removed
  ]
})
export class UploadDialogComponent {
  @ViewChild(MetadataEditorComponent) metadataEditor?: MetadataEditorComponent; // ❌ Removed
  metadataValid: boolean = true;     // ❌ Removed
  currentMetadata: { [key: string]: any } = {}; // ❌ Removed
}

// upload-dialog.component.ts - AFTER
export interface UploadDialogResult {
  files: File[];
  allowDuplicates: boolean;  // ✅ Simplified
}

@Component({
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    // ... no metadata editor
  ]
})
export class UploadDialogComponent {
  files: File[] = [];
  totalSize: number = 0;
  allowDuplicates: boolean = false;  // ✅ Clean and simple
}
```

### 4. File Explorer - Auto-Open Logic

```typescript
// file-explorer.component.ts

export class FileExplorerComponent extends FileOperationsComponent {
  metadataPanelOpen: boolean = false;
  selectedDocumentForMetadata?: string;

  private handleFileUpload(files: FileList) {
    const fileArray = Array.from(files);
    const dialogRef = this.dialog.open(UploadDialogComponent, {
      width: '700px',
      data: { files: fileArray, parentFolderId: this.currentFolder?.id }
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        const isSingleFile = result.files.length === 1;
        const singleFileName = isSingleFile ? result.files[0].name : undefined;

        this.documentApi.uploadMultipleDocuments(
          result.files,
          this.currentFolder?.id,
          result.allowDuplicates
        ).subscribe({
          complete: () => {
            this.snackBar.open(`Files uploaded successfully`, 'Close', { duration: 3000 });

            // ✅ Auto-open metadata panel for single file upload
            if (isSingleFile && singleFileName) {
              this.documentApi.listFolder(this.currentFolder?.id, this.pageIndex + 1, this.pageSize)
                .subscribe({
                  next: (response) => {
                    this.populateFolderContents(response);

                    // Find the uploaded file by name
                    const uploadedItem = this.items.find(item => item.name === singleFileName);
                    if (uploadedItem) {
                      setTimeout(() => {
                        this.openMetadataPanel(uploadedItem.id);
                      }, 300);
                    }
                  }
                });
            } else {
              // ❌ Multiple files - NO auto-open
              this.loadFolder(this.currentFolder);
            }
          }
        });
      }
    });
  }

  openMetadataPanel(documentId: string) {
    this.selectedDocumentForMetadata = documentId;
    this.metadataPanelOpen = true;
  }

  closeMetadataPanel() {
    this.metadataPanelOpen = false;
    this.selectedDocumentForMetadata = undefined;
  }

  // Properties action now uses panel instead of dialog
  onViewProperties(item: FileItem) {
    this.openMetadataPanel(item.id);
  }
}
```

### 5. Template Integration

```html
<!-- file-explorer.component.ts template -->
<div class="file-explorer-container">
  <!-- Existing toolbar and file grid/list -->
  <app-toolbar ...></app-toolbar>
  <div class="file-explorer-content">
    <app-file-grid ...></app-file-grid>
    <app-file-list ...></app-file-list>
  </div>

  <!-- ✅ NEW: Metadata Panel -->
  <app-metadata-panel
    [documentId]="selectedDocumentForMetadata"
    [isOpen]="metadataPanelOpen"
    (closePanel)="closeMetadataPanel()"
    (metadataSaved)="onMetadataSaved()">
  </app-metadata-panel>
</div>
```

### 6. Panel HTML Structure

```html
<!-- metadata-panel.component.html -->

<!-- Overlay (clickable to close) -->
<div class="metadata-panel-overlay"
     *ngIf="isOpen"
     (click)="onClose()"
     [@slideInOut]="animationState">
</div>

<!-- Panel itself -->
<div class="metadata-panel"
     *ngIf="isOpen"
     [@slideInOut]="animationState"
     (click)="$event.stopPropagation()">

  <!-- Header with gradient -->
  <div class="panel-header">
    <div class="header-content">
      <mat-icon class="header-icon">info</mat-icon>
      <h2>Properties</h2>
    </div>
    <button mat-icon-button (click)="onClose()">
      <mat-icon>close</mat-icon>
    </button>
  </div>

  <!-- Scrollable body -->
  <div class="panel-body">
    <!-- General Information Section -->
    <div class="info-section">
      <div class="section-header">
        <mat-icon>description</mat-icon>
        <h3>General Information</h3>
      </div>
      <div class="info-content">
        <div class="info-row">
          <span class="info-label">Name:</span>
          <span class="info-value">{{ documentInfo.name }}</span>
        </div>
        <!-- More info rows... -->
      </div>
    </div>

    <!-- Metadata Section -->
    <div class="metadata-section">
      <div class="section-header">
        <div class="section-title">
          <mat-icon>label</mat-icon>
          <h3>Custom Metadata</h3>
        </div>
        <div class="section-actions">
          <!-- Edit/Save/Cancel buttons -->
        </div>
      </div>

      <!-- Reuses existing metadata-editor component -->
      <app-metadata-editor
        [metadata]="currentMetadata"
        [readonly]="!editMode"
        [showTitle]="false"
        (metadataChange)="onMetadataChange($event)"
        (valid)="onValidChange($event)">
      </app-metadata-editor>
    </div>
  </div>

  <!-- Optional footer for actions -->
  <div class="panel-footer" *ngIf="documentInfo && editMode">
    <button mat-button (click)="toggleEditMode()">Cancel</button>
    <button mat-raised-button color="primary" (click)="saveMetadata()">
      Save Changes
    </button>
  </div>
</div>
```

### 7. Theme CSS Variables (Reference)

```css
/* From custom-theme.scss and global_styles.css */
:root {
  --primary: #6366f1;           /* Indigo */
  --primary-light: #818cf8;
  --primary-dark: #4f46e5;
  --secondary: #14b8a6;          /* Teal */
  --accent: #f59e0b;             /* Amber */
  --bg-primary: #ffffff;
  --bg-secondary: #f8fafc;
  --bg-tertiary: #f1f5f9;
  --text-primary: #1e293b;
  --text-secondary: #64748b;
  --text-tertiary: #94a3b8;
  --border-color: #e2e8f0;
  --border-light: #f1f5f9;
  --shadow-sm: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  --shadow-md: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
  --shadow-lg: 0 10px 15px -3px rgba(0, 0, 0, 0.1);
}
```

### 8. Responsive Design

```css
/* Mobile-first approach */
@media (max-width: 768px) {
  .metadata-panel {
    width: 100%;           /* Full screen on mobile */
    max-width: 100vw;
  }

  .panel-header {
    padding: 16px 20px;    /* Tighter padding */
  }

  .panel-body {
    padding: 16px;
  }

  .info-section,
  .metadata-section {
    padding: 16px;
  }
}
```

### 9. Upload Dialog Info Note

```html
<!-- upload-dialog.component.html -->
<div class="info-note">
  <mat-icon>info</mat-icon>
  <p>You can add metadata to uploaded files after upload using the properties panel</p>
</div>
```

```css
/* upload-dialog.component.css */
.info-note {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background-color: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  color: #1e40af;
}
```

### 10. Status Indicators

```html
<!-- Saving indicator -->
<div class="saving-indicator" *ngIf="saving">
  <mat-spinner diameter="20"></mat-spinner>
  <span>Saving changes...</span>
</div>

<!-- Validation warning -->
<div class="validation-warning" *ngIf="editMode && !metadataValid">
  <mat-icon>warning</mat-icon>
  Please fix metadata errors before saving
</div>

<!-- Unsaved changes -->
<div class="changes-indicator" *ngIf="editMode && hasChanges">
  <mat-icon>info</mat-icon>
  You have unsaved changes
</div>
```

---

## Component Architecture

```
file-explorer.component
├── metadata-panel.component (NEW)
│   ├── metadata-editor.component (REUSED)
│   └── document-api.service
├── upload-dialog.component (SIMPLIFIED)
├── file-grid.component
└── file-list.component
```

## Data Flow

```
User Action (Upload/Properties)
    ↓
File Explorer Component
    ↓
Open Metadata Panel (set documentId, isOpen=true)
    ↓
Metadata Panel Component
    ↓
Load Document Info from API
    ↓
Display in Panel (General Info + Metadata Editor)
    ↓
User Edits Metadata
    ↓
Save to API
    ↓
Emit metadataSaved event
    ↓
File Explorer reloads folder
```

---

## Key Takeaways

1. **Component Reusability**: The `metadata-editor` component is reused without modification
2. **Clean Separation**: Upload and metadata editing are now separate concerns
3. **Progressive Enhancement**: Auto-open feature enhances UX without being intrusive
4. **Theme Consistency**: All colors, shadows, and spacing match the existing design system
5. **Modern Angular**: Uses standalone components, animations, and reactive patterns
6. **Type Safety**: Full TypeScript typing throughout
7. **Accessibility**: ARIA labels, keyboard navigation, and focus management
