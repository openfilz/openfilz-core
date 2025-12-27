import { Component, inject } from '@angular/core';

import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { KeyboardShortcutsService } from '../../services/keyboard-shortcuts.service';

interface ShortcutGroup {
  category: string;
  shortcuts: Array<{
    keys: string;
    description: string;
  }>;
}

@Component({
  selector: 'app-keyboard-shortcuts-dialog',
  standalone: true,
  templateUrl: './keyboard-shortcuts-dialog.component.html',
  styleUrls: ['./keyboard-shortcuts-dialog.component.css'],
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule
]
})
export class KeyboardShortcutsDialogComponent {
  shortcutGroups: ShortcutGroup[] = [
    {
      category: 'File Management',
      shortcuts: [
        { keys: 'Ctrl+U', description: 'Upload files' },
        { keys: 'Ctrl+N', description: 'Create new folder' },
        { keys: 'Ctrl+D', description: 'Download selected items' },
        { keys: 'F2', description: 'Rename selected item' },
        { keys: 'Delete', description: 'Delete selected items' }
      ]
    },
    {
      category: 'Selection',
      shortcuts: [
        { keys: 'Ctrl+A', description: 'Select all items' },
        { keys: 'Escape', description: 'Clear selection' },
        { keys: 'Space', description: 'Toggle item selection (in grid/list)' }
      ]
    },
    {
      category: 'Copy & Move',
      shortcuts: [
        { keys: 'Ctrl+Shift+C', description: 'Copy selected items' },
        { keys: 'Ctrl+X', description: 'Move selected items' }
      ]
    },
    {
      category: 'Navigation',
      shortcuts: [
        { keys: '↑ ↓ ← →', description: 'Navigate between items' },
        { keys: 'Enter', description: 'Open folder or file' },
        { keys: 'Home', description: 'Go to first item' },
        { keys: 'End', description: 'Go to last item' }
      ]
    },
    {
      category: 'General',
      shortcuts: [
        { keys: '?', description: 'Show keyboard shortcuts' },
        { keys: 'Escape', description: 'Close dialogs' }
      ]
    }
  ];

  public dialogRef = inject(MatDialogRef<KeyboardShortcutsDialogComponent>);

  constructor() { }

  onClose(): void {
    this.dialogRef.close();
  }

  // Parse keyboard shortcut string to separate keys
  parseShortcutKeys(keys: string): string[] {
    return keys.split('+').map(key => key.trim());
  }
}
