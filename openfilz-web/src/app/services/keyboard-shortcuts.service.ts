import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Subject, fromEvent, filter, map } from 'rxjs';

export interface KeyboardShortcut {
  key: string;
  ctrlKey?: boolean;
  altKey?: boolean;
  shiftKey?: boolean;
  description: string;
  action: () => void;
  context?: string; // Optional context for context-specific shortcuts
}

export interface ShortcutGroup {
  name: string;
  shortcuts: KeyboardShortcut[];
}

@Injectable({
  providedIn: 'root'
})
export class KeyboardShortcutsService {
  private router = inject(Router);
  private shortcuts = new Map<string, KeyboardShortcut>();
  private shortcutTriggered = new Subject<KeyboardShortcut>();

  // Observable for components to listen to shortcut events
  public shortcutTriggered$ = this.shortcutTriggered.asObservable();

  // Track if help dialog is open to prevent triggering shortcuts
  private isHelpDialogOpen = false;

  constructor() {
    this.initializeGlobalShortcuts();
    this.setupKeyboardListener();
  }

  /**
   * Register a new keyboard shortcut
   */
  registerShortcut(shortcut: KeyboardShortcut): void {
    const key = this.getShortcutKey(shortcut);
    this.shortcuts.set(key, shortcut);
  }

  /**
   * Unregister a keyboard shortcut
   */
  unregisterShortcut(key: string, ctrlKey = false, altKey = false, shiftKey = false): void {
    const shortcutKey = this.createKey(key, ctrlKey, altKey, shiftKey);
    this.shortcuts.delete(shortcutKey);
  }

  /**
   * Get all registered shortcuts grouped by category
   */
  getAllShortcuts(): ShortcutGroup[] {
    const groups: ShortcutGroup[] = [
      { name: 'Navigation', shortcuts: [] },
      { name: 'File Operations', shortcuts: [] },
      { name: 'Search & Filter', shortcuts: [] },
      { name: 'Selection', shortcuts: [] },
      { name: 'General', shortcuts: [] }
    ];

    this.shortcuts.forEach(shortcut => {
      const context = shortcut.context || 'General';
      const group = groups.find(g => g.name === context) || groups[groups.length - 1];
      group.shortcuts.push(shortcut);
    });

    return groups.filter(g => g.shortcuts.length > 0);
  }

  /**
   * Set help dialog state
   */
  setHelpDialogState(isOpen: boolean): void {
    this.isHelpDialogOpen = isOpen;
  }

  /**
   * Initialize global keyboard shortcuts
   */
  private initializeGlobalShortcuts(): void {
    // Help dialog
    this.registerShortcut({
      key: '?',
      shiftKey: true,
      description: 'Show keyboard shortcuts',
      context: 'General',
      action: () => {
        this.shortcutTriggered.next({
          key: '?',
          shiftKey: true,
          description: 'Show keyboard shortcuts',
          action: () => {}
        });
      }
    });

    // Escape key - Cancel/Close
    this.registerShortcut({
      key: 'Escape',
      description: 'Cancel current action or close dialog',
      context: 'General',
      action: () => {
        this.shortcutTriggered.next({
          key: 'Escape',
          description: 'Cancel',
          action: () => {}
        });
      }
    });
  }

  /**
   * Setup global keyboard event listener
   */
  private setupKeyboardListener(): void {
    fromEvent<KeyboardEvent>(document, 'keydown')
      .pipe(
        filter(event => this.shouldHandleEvent(event)),
        map(event => this.handleKeyboardEvent(event))
      )
      .subscribe();
  }

  /**
   * Check if the event should be handled
   */
  private shouldHandleEvent(event: KeyboardEvent): boolean {
    const target = event.target as HTMLElement;
    const tagName = target.tagName.toLowerCase();

    // Don't handle shortcuts if user is typing in an input/textarea
    if (tagName === 'input' || tagName === 'textarea' || target.isContentEditable) {
      // Allow Escape key even in inputs
      if (event.key === 'Escape') {
        return true;
      }
      return false;
    }

    // Don't handle if help dialog is open (except for Escape)
    if (this.isHelpDialogOpen && event.key !== 'Escape') {
      return false;
    }

    return true;
  }

  /**
   * Handle keyboard event
   */
  private handleKeyboardEvent(event: KeyboardEvent): void {
    const key = this.createKey(
      event.key,
      event.ctrlKey || event.metaKey, // Support both Ctrl and Cmd
      event.altKey,
      event.shiftKey
    );

    const shortcut = this.shortcuts.get(key);

    if (shortcut) {
      event.preventDefault();
      event.stopPropagation();
      shortcut.action();
      this.shortcutTriggered.next(shortcut);
    }
  }

  /**
   * Create a unique key for the shortcut map
   */
  private createKey(key: string, ctrlKey: boolean, altKey: boolean, shiftKey: boolean): string {
    const modifiers: string[] = [];
    if (ctrlKey) modifiers.push('ctrl');
    if (altKey) modifiers.push('alt');
    if (shiftKey) modifiers.push('shift');

    return modifiers.length > 0
      ? `${modifiers.join('+')}+${key.toLowerCase()}`
      : key.toLowerCase();
  }

  /**
   * Get shortcut key from shortcut object
   */
  private getShortcutKey(shortcut: KeyboardShortcut): string {
    return this.createKey(
      shortcut.key,
      shortcut.ctrlKey || false,
      shortcut.altKey || false,
      shortcut.shiftKey || false
    );
  }

  /**
   * Format shortcut for display (e.g., "Ctrl+U")
   */
  static formatShortcut(shortcut: KeyboardShortcut): string {
    const parts: string[] = [];

    if (shortcut.ctrlKey) {
      parts.push(this.isMac() ? '⌘' : 'Ctrl');
    }
    if (shortcut.altKey) {
      parts.push(this.isMac() ? '⌥' : 'Alt');
    }
    if (shortcut.shiftKey) {
      parts.push(this.isMac() ? '⇧' : 'Shift');
    }

    // Format special keys
    let keyDisplay = shortcut.key;
    if (keyDisplay === 'Escape') keyDisplay = 'Esc';
    if (keyDisplay === 'Delete') keyDisplay = 'Del';
    if (keyDisplay === 'ArrowUp') keyDisplay = '↑';
    if (keyDisplay === 'ArrowDown') keyDisplay = '↓';
    if (keyDisplay === 'ArrowLeft') keyDisplay = '←';
    if (keyDisplay === 'ArrowRight') keyDisplay = '→';

    parts.push(keyDisplay);

    return parts.join('+');
  }

  /**
   * Check if running on Mac
   */
  private static isMac(): boolean {
    return /Mac|iPhone|iPod|iPad/i.test(navigator.platform);
  }
}
