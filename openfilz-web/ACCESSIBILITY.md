# OpenFilz Web - Accessibility Guide

This document outlines the accessibility features implemented in OpenFilz Web and provides guidance for developers and users.

## Table of Contents

1. [Overview](#overview)
2. [WCAG 2.1 AA Compliance](#wcag-21-aa-compliance)
3. [Keyboard Navigation](#keyboard-navigation)
4. [Screen Reader Support](#screen-reader-support)
5. [Visual Accessibility](#visual-accessibility)
6. [Testing Procedures](#testing-procedures)
7. [Developer Guidelines](#developer-guidelines)

---

## Overview

OpenFilz Web is committed to providing an accessible document management experience for all users. The application follows WCAG 2.1 Level AA standards and implements comprehensive keyboard navigation, screen reader support, and visual accessibility features.

**Key Accessibility Features:**
- Full keyboard navigation with visible focus indicators
- Comprehensive ARIA labels and landmarks
- Screen reader compatibility (NVDA, JAWS, VoiceOver)
- Semantic HTML structure
- High contrast mode support
- Reduced motion support
- Minimum 44x44px touch targets
- Material Icons for consistent iconography

---

## WCAG 2.1 AA Compliance

### Perceivable
- **Text Alternatives (1.1.1):** All images and icons have appropriate alt text or aria-hidden attributes
- **Audio/Video Alternatives (1.2):** Not applicable (no media content)
- **Adaptable (1.3):** Semantic HTML5 elements (nav, main, aside, section, time)
- **Distinguishable (1.4):** High contrast colors, focus indicators, no color-only information

### Operable
- **Keyboard Accessible (2.1):** Full keyboard navigation for all interactive elements
- **Enough Time (2.2):** No time limits on user interactions
- **Seizures (2.3):** No flashing content
- **Navigable (2.4):** Skip links, landmarks, breadcrumbs, page titles
- **Input Modalities (2.5):** 44x44px minimum touch targets, label in name

### Understandable
- **Readable (3.1):** Language specified, clear labels
- **Predictable (3.2):** Consistent navigation, no unexpected context changes
- **Input Assistance (3.3):** Clear error messages, labels, instructions

### Robust
- **Compatible (4.1):** Valid HTML, proper ARIA usage, name/role/value for all controls

---

## Keyboard Navigation

### Global Shortcuts

Press **?** (Shift+/) at any time to view all keyboard shortcuts.

| Shortcut | Action |
|----------|--------|
| **?** | Show keyboard shortcuts help dialog |
| **Escape** | Close dialogs / Clear selection |

### File Management

| Shortcut | Action |
|----------|--------|
| **Ctrl+U** | Upload files |
| **Ctrl+N** | Create new folder |
| **Ctrl+D** | Download selected items |
| **F2** | Rename selected item |
| **Delete** | Delete selected items |

### Selection

| Shortcut | Action |
|----------|--------|
| **Ctrl+A** | Select all items |
| **Escape** | Clear selection |
| **Space** | Toggle item selection (in grid/list) |

### Copy & Move

| Shortcut | Action |
|----------|--------|
| **Ctrl+Shift+C** | Copy selected items |
| **Ctrl+X** | Move selected items |

### Navigation (Grid/List View)

| Shortcut | Action |
|----------|--------|
| **↑ ↓ ← →** | Navigate between items |
| **Enter** | Open folder or file |
| **Home** | Go to first item |
| **End** | Go to last item |

### Implementation Details

**Keyboard Shortcuts Service:**
- Location: `src/app/services/keyboard-shortcuts.service.ts`
- Provides centralized keyboard event handling
- Supports context-aware shortcuts
- Conflict detection for shortcut registration
- Mac/Windows compatibility (Command vs Control)

**Integration Points:**
- File Explorer Component (`file-explorer.component.ts:219-341`)
- File Grid Component (`file-grid.component.ts`)
- File List Component (`file-list.component.ts`)

---

## Screen Reader Support

### Tested Screen Readers
- NVDA (Windows) - Primary testing
- JAWS (Windows)
- VoiceOver (macOS/iOS)
- TalkBack (Android)

### ARIA Landmarks

All major sections use proper ARIA landmarks:

```html
<main role="main" aria-label="Dashboard">
<nav role="navigation" aria-label="Breadcrumb">
<aside role="complementary" aria-label="Document properties panel">
<div role="toolbar" aria-label="File management toolbar">
```

### Live Regions

Dynamic content is announced using ARIA live regions:

```html
<div role="status" aria-live="polite" aria-busy="true">
  <mat-spinner aria-hidden="true"></mat-spinner>
  <span>Loading...</span>
</div>

<div role="alert">
  <mat-icon aria-hidden="true">error</mat-icon>
  <span>{{ errorMessage }}</span>
</div>
```

### Form Labels

All form inputs have proper labels:

```html
<input matInput
       [(ngModel)]="folderName"
       aria-label="Folder name"
       aria-required="true">
```

### Button Labels

All icon-only buttons have accessible labels:

```html
<button mat-icon-button
        aria-label="Close dialog"
        (click)="onClose()">
  <mat-icon aria-hidden="true">close</mat-icon>
</button>
```

### Decorative Icons

All decorative icons are hidden from screen readers:

```html
<mat-icon aria-hidden="true">folder</mat-icon>
```

---

## Visual Accessibility

### Focus Indicators

Custom focus-visible styles ensure keyboard navigation is clearly visible:

```css
*:focus-visible,
button:focus-visible,
a:focus-visible,
[tabindex]:focus-visible {
  outline: 2px solid var(--primary) !important;
  outline-offset: 2px !important;
  border-radius: 4px;
}
```

### Skip to Main Content

Skip link for keyboard users to bypass navigation:

```css
.skip-to-main {
  position: absolute;
  top: -40px;
  left: 0;
  z-index: 100;
}

.skip-to-main:focus {
  top: 0;
}
```

### Touch Targets

All interactive elements meet WCAG AA minimum size:

```css
button, a, .mat-mdc-button {
  min-width: 44px;
  min-height: 44px;
}
```

### Reduced Motion

Respects user's motion preferences:

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

### High Contrast Mode

Support for Windows High Contrast Mode:

```css
@media (prefers-contrast: high) {
  .card, .panel {
    border: 2px solid currentColor;
  }
}
```

### Color Contrast

All text meets WCAG AA contrast ratios:
- Normal text: 4.5:1 minimum
- Large text (18pt+): 3:1 minimum
- UI components: 3:1 minimum

---

## Testing Procedures

### Automated Testing

**1. Lighthouse Accessibility Audit**

```bash
# Install Lighthouse CLI
npm install -g @lhci/cli

# Run audit
lighthouse http://localhost:4200 --only-category=accessibility --view
```

**Expected Score:** 95+ / 100

**2. axe DevTools**

1. Install axe DevTools browser extension
2. Open application in browser
3. Open DevTools → axe DevTools tab
4. Click "Scan ALL of my page"
5. Review and fix any issues

**Target:** Zero violations

### Manual Keyboard Testing

**Basic Navigation Test:**
1. Load application with keyboard only (no mouse)
2. Press Tab to navigate through all interactive elements
3. Verify focus indicator is always visible
4. Verify logical tab order
5. Test all keyboard shortcuts listed above

**Grid/List Navigation Test:**
1. Navigate to file explorer
2. Use arrow keys to navigate between items
3. Press Space to select items
4. Press Enter to open folders/files
5. Press Home/End to jump to first/last item

**Dialog Test:**
1. Press Ctrl+N to open folder creation dialog
2. Verify focus moves to input field
3. Type folder name and press Enter
4. Verify dialog closes and folder appears
5. Press ? to open keyboard shortcuts dialog
6. Press Escape to close

### Screen Reader Testing

**NVDA Testing (Windows):**
1. Install NVDA (https://www.nvaccess.org/)
2. Start NVDA (Ctrl+Alt+N)
3. Navigate application using keyboard only
4. Verify all elements are announced correctly
5. Verify ARIA labels are read properly
6. Verify live regions announce updates

**Test Checklist:**
- [ ] All buttons announce their purpose
- [ ] Form fields have clear labels
- [ ] Loading states are announced
- [ ] Error messages are announced
- [ ] Dynamic content updates are announced
- [ ] Landmarks are recognized
- [ ] Navigation is logical

**VoiceOver Testing (macOS):**
1. Enable VoiceOver (Cmd+F5)
2. Use Cmd+Arrow keys to navigate
3. Verify rotor navigation works (Cmd+U)
4. Test form navigation (Cmd+Option+J)

### Browser Testing

Test accessibility features in:
- Chrome/Edge (latest)
- Firefox (latest)
- Safari (latest)

### Mobile Testing

Test touch accessibility:
- All touch targets are at least 44x44px
- Swipe gestures work properly
- Screen reader navigation works (TalkBack/VoiceOver)

---

## Developer Guidelines

### Adding New Components

**1. Semantic HTML First**

Use semantic elements instead of divs:

```html
<!-- ❌ Bad -->
<div class="navigation">
  <div class="link">Home</div>
</div>

<!-- ✅ Good -->
<nav role="navigation" aria-label="Main navigation">
  <a href="/home">Home</a>
</nav>
```

**2. ARIA Labels for All Interactive Elements**

```html
<!-- Icon-only buttons -->
<button mat-icon-button
        aria-label="Delete file"
        (click)="onDelete()">
  <mat-icon aria-hidden="true">delete</mat-icon>
</button>

<!-- Complex widgets -->
<div role="grid" aria-label="Files and folders">
  <!-- Grid content -->
</div>
```

**3. Keyboard Support**

All custom interactive components must support keyboard:

```typescript
@HostListener('keydown', ['$event'])
handleKeyboardEvent(event: KeyboardEvent) {
  switch (event.key) {
    case 'Enter':
    case ' ':
      event.preventDefault();
      this.handleActivation();
      break;
    case 'Escape':
      event.preventDefault();
      this.handleCancel();
      break;
  }
}
```

**4. Focus Management**

Manage focus for dynamic content:

```typescript
// After opening dialog
ngAfterViewInit() {
  setTimeout(() => {
    this.inputField?.nativeElement?.focus();
  }, 100);
}

// After closing dialog
dialogRef.afterClosed().subscribe(() => {
  // Return focus to trigger element
  this.triggerButton?.nativeElement?.focus();
});
```

**5. Loading States**

Use proper ARIA attributes for loading states:

```html
<div role="status" aria-live="polite" aria-busy="true">
  <mat-spinner aria-hidden="true"></mat-spinner>
  <p>Loading files...</p>
</div>
```

**6. Form Validation**

Provide clear error messages:

```html
<mat-form-field>
  <input matInput
         aria-label="Email address"
         aria-required="true"
         aria-invalid="{{emailInvalid}}"
         aria-describedby="email-error">
  <mat-error id="email-error">
    Please enter a valid email address
  </mat-error>
</mat-form-field>
```

**7. Testing Before Commit**

Before committing new components:
- [ ] Test with keyboard only (Tab, Enter, Space, Escape)
- [ ] Test with screen reader (NVDA/VoiceOver)
- [ ] Run Lighthouse accessibility audit
- [ ] Verify focus indicators are visible
- [ ] Check color contrast ratios

### Code Review Checklist

When reviewing accessibility-related PRs:

- [ ] All images/icons have alt text or aria-hidden
- [ ] All buttons have accessible labels
- [ ] Form inputs have associated labels
- [ ] Keyboard navigation works properly
- [ ] Focus order is logical
- [ ] ARIA attributes are used correctly
- [ ] No color-only information
- [ ] Touch targets are at least 44x44px
- [ ] Loading states use aria-live
- [ ] Error messages use role="alert"

---

## File Reference

### Modified Files

**Global Styles:**
- `src/global_styles.css` - Accessibility CSS (~150 lines added)

**Services:**
- `src/app/services/keyboard-shortcuts.service.ts` - New service

**Components:**
- `src/app/components/toolbar/toolbar.component.html`
- `src/app/components/toolbar/toolbar.component.ts`
- `src/app/components/file-grid/file-grid.component.html`
- `src/app/components/file-grid/file-grid.component.ts`
- `src/app/components/file-list/file-list.component.html`
- `src/app/components/file-list/file-list.component.ts`
- `src/app/components/search-results/search-results.component.html`
- `src/app/components/search-results/search-results.component.ts`
- `src/app/components/breadcrumb/breadcrumb.component.html`
- `src/app/components/metadata-panel/metadata-panel.component.html`
- `src/app/components/dashboard/dashboard.component.html`
- `src/app/components/file-explorer/file-explorer.component.ts`

**Dialogs:**
- `src/app/dialogs/create-folder-dialog/create-folder-dialog.component.html`
- `src/app/dialogs/rename-dialog/rename-dialog.component.html`
- `src/app/dialogs/keyboard-shortcuts-dialog/` - New dialog

**Dependencies:**
- `src/index.html` - Removed Font Awesome CDN
- All Font Awesome icons replaced with Material Icons (37+ instances)

---

## Additional Resources

- [WCAG 2.1 Guidelines](https://www.w3.org/WAI/WCAG21/quickref/)
- [ARIA Authoring Practices Guide](https://www.w3.org/WAI/ARIA/apg/)
- [WebAIM Keyboard Accessibility](https://webaim.org/techniques/keyboard/)
- [axe DevTools](https://www.deque.com/axe/devtools/)
- [NVDA Screen Reader](https://www.nvaccess.org/)

---

## Support

For accessibility issues or questions:
- Create an issue in the GitHub repository
- Label with "accessibility"
- Provide details about the issue and how to reproduce

---

**Last Updated:** 2025-11-24
**WCAG Compliance:** 2.1 Level AA
**Status:** Implemented & Tested
