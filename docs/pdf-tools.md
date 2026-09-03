# PDF Tools — design & implementation plan

> Status (2026-09-03): **backend + AI/MCP tools implemented in `openfilz-core` (phases 1–2) and the
> `openfilz-web` UI implemented (phase 3) — both uncommitted on `develop`; integration/release (phase 4:
> EE submodule bump + native build check, `openfilz-web-ee` upstream pull + descriptor mirror, demo-e2e,
> site) pending.** Operator reference: `docs/admin-guide.md` → *PDF Tools*; tool reference: `docs/mcp.md`;
> frontend map: `openfilz-web/CLAUDE.md` → *PDF tools*. Top-level bookmarks of a source survive
> organize / rotate / split (re-pointed at the surviving pages); nested levels are flattened.
> Scope: Community core (`openfilz-core` + `openfilz-web`). Enterprise inherits it through the
> core submodule bump and the `openfilz-web-ee` upstream pull.

## 1. Goal

Make OpenFilz CE the best free place to **merge, split, rotate, reorder, delete and extract PDF
pages** — the whole PDFsam Basic feature set plus the "Organize" family of iLovePDF — directly on
documents already stored in the DMS, with a first-class visual UX and full exposure through:

1. the **REST API** (primary surface, which also feeds the generated SDKs),
2. the **AI assistant chat** and the **MCP server** (same tool objects, CE and EE),
3. the **Angular UI** (selection bar, context menu, file viewer, mobile sheet).

What makes this better than a standalone tool: files never leave the deployment, every output is
a real document with **audit trail, version history, permissions, quota, checksum, thumbnails and
full-text indexing**, and an agent can chain it with everything else the DMS offers
("merge the three invoices of Acme into one and share it").

### 1.1 Feature coverage target

| Capability | PDFsam Basic | iLovePDF | OpenFilz today | Plan |
|---|---|---|---|---|
| Merge (with per-source page ranges, bookmarks per source) | ✅ | ✅ | — | **v1** |
| Split every N pages / at pages / custom ranges / every page | ✅ | ✅ | — | **v1** |
| Split by bookmark (outline level) | ✅ | — | — | **v1** |
| Extract pages to a new document | ✅ | ✅ | — | **v1** |
| Rotate (whole document, batch of documents, per page) | ✅ | ✅ | viewer only (not saved) | **v1** |
| Organize: visual reorder / delete / duplicate / rotate per page | Enhanced (paid) | ✅ | — | **v1** |
| Insert pages from another PDF | Enhanced | ✅ | — | v1.1 |
| Alternate mix (interleave front/back scans) | ✅ | — | — | v1.1 |
| Split by size | ✅ | — | — | v2 |
| Compress | — | ✅ | Ghostscript in EE `archiving-api` only | v2 (CE: image downsample; EE: Ghostscript quality) |
| Watermark / page numbers / stamp text | — | ✅ | e-Sign stamping code (ASCII only) | v2 (needs a bundled Unicode font) |
| Protect / unlock (password) | — | ✅ | Bouncy Castle already present | v2 |
| PDF → images / images → PDF | — | ✅ | `PDFRenderer` used for thumbnails | v2 |
| Sign | — | ✅ | **e-Sign** | done |
| PDF → PDF/A, OCR, Office ↔ PDF | — | ✅ | EE (`archiving-api`, `scan-ingestion-api`, OnlyOffice/Gotenberg) | stays EE |

## 2. Assessment

**Backend: ~80 % of the machinery exists.** PDFBox 3.0.8 is already a dependency (pinned to the
Tika version — never bump it independently), used by `SignaturePdfServiceImpl`,
`ThumbnailServiceImpl` and `DocumentAiTools`. The write pipelines we need are the regular ones:
`DocumentService.uploadDocument(...)` (new sibling document) and
`DocumentService.replaceDocumentContent(...)` (new version of the same document). Both give us
storage, MinIO versioning, audit, checksum, quota, thumbnail regeneration and re-indexing for free
(`SaveDocumentServiceImpl.doSaveFile` / `saveAndReplaceDocument`, fan-out in
`DefaultMetadataPostProcessor`). `PathFilePart` / `InMemoryFilePart` adapt server-produced bytes
to that pipeline (precedent: `AbstractOnlyOfficeService` and `DocumentAiTools.writeFile`).
The e-Sign feature is the template for a runtime toggle (`openfilz.signature.active` → 404 when
off → `Settings.signatureActive`). The MCP/AI tool layer already has the extension seam
(`McpToolContributor` + `ToolCapability` + `AiToolRolePolicy`).

**Frontend: the seams exist, the page grid does not.** Actions are descriptor-driven
(`models/file-actions.ts`, mirrored by `file-actions-ee.ts` in EE), pdf.js 4.10 is already
bundled (worker in `utils/pdfjs-worker.ts`), dialogs follow a documented skeleton, and
`@angular/cdk/drag-drop` 21.2 is installed but unused. Nothing renders page thumbnails today; the
one-page-at-a-time render + cancellation logic of `request-signature-dialog` is the starting
point for a lazy thumbnail grid.

**Risks, with the decision taken for each**

| Risk | Decision |
|---|---|
| Memory / CPU on the API process (native image, shared with uploads) | PDFBox in-process, temp-file backed (`RandomAccessReadBufferedFile` + `IOUtils.createTempFileOnlyStreamCache()`), on `boundedElastic`, bounded by `openfilz.pdf-tools.max-*` limits and a small concurrency semaphore. No external service: works with `thumbnail.active=false`, no Gotenberg. |
| New POST paths are 403 by default | `AbstractSecurityService.isInsertOrUpdateAccess` must allow-list `/api/v1/pdf/**` (CONTRIBUTOR). `WormSecurityServiceImpl` mirrors the existing rule: new documents allowed, in-place replace refused. |
| Digitally signed PDFs (e-Sign output, AATL seal) become invalid after any change | `/info` reports `signed`; the UI warns and defaults to *new document*; the backend refuses `NEW_VERSION` on a signed PDF unless `acknowledgeSignatureLoss=true` (409). Documents attached to an **active e-Sign envelope** are refused for in-place edits — `/info` reports `activeSignatureEnvelope` so the UI disables *new version* up front instead of surfacing the 409. |
| Encrypted PDFs | v1 refuses with a 422 `PDF_ENCRYPTED`; v2 adds unlock/protect. |
| Generated SDKs break on anonymous schemas / enum quirks | Every DTO is a named top-level record; enum fields verified against the springdoc `swagger-annotations` clash (see core `CLAUDE.md` §17); SDK generation is a phase-1 exit criterion. |
| GraalVM native (built by EE, CE is JVM) | Page-tree operations are COS-level and PDFBox ships its own native metadata; add `PdfBoxRuntimeHints` only if the EE native build proves it necessary. Verify the native build in phase 4, not at the end. |
| Fork friction in `openfilz-web-ee` | Everything in new files; shared files get one descriptor line + one `switch` case each. EE mirrors the descriptors in `file-actions-ee.ts` after the upstream pull. |
| Long operations over HTTP | v1 is synchronous with limits (a 100 MB merge is seconds). Async job + progress is the v2 escape hatch if real usage demands it. |

## 3. Backend design (openfilz-core)

### 3.1 One engine, several verbs

Every operation is a **page composition**: an ordered list of `(sourceDocumentId, pageNumber,
rotationDelta)` producing one output PDF. Merge, reorder, rotate, delete, duplicate, extract and
insert-from-another-PDF are all one composition; split is *N compositions*. This keeps the
PDFBox code in a single, exhaustively unit-tested class:

```
service/pdf/
  PdfCompositionEngine        pure PDFBox: compose(List<PageRef>, OutlineSpec, Path out) ; inspect(Path) -> PdfInfo
  PageRangeParser             "1-3,5,8-" -> List<Integer> (bounds-checked, unit-tested)
  PdfToolsService (+ impl)    orchestration: resolve & download sources, limits, engine, write-back, audit, temp cleanup
  PdfToolsProperties          @ConfigurationProperties("openfilz.pdf-tools")
controller/rest/PdfToolsController      RestApiVersion.ENDPOINT_PDF = "/pdf"
dto/request/pdf/*, dto/response/pdf/*   named records only
exception/PdfToolsException             -> 422 (encrypted, not a PDF, bad ranges), 409 (signed / envelope)
```

Engine details: `Loader.loadPDF(new RandomAccessReadBufferedFile(path), IOUtils.createTempFileOnlyStreamCache())`
per source, `out.importPage(page)` per `PageRef` (keeps resources/annotations; rotation applied as
`(page.getRotation() + delta) % 360`), optional outline entries per source (`PDDocumentOutline`),
`Producer = "OpenFilz"`, `Title = output name`, save with a temp-file stream cache. Guards:
`isEncrypted()`, `getSignatureDictionaries()` non-empty → `signed`, page count and byte limits.

### 3.2 REST API (`/api/v1/pdf`, CONTRIBUTOR for writes, READER for reads)

| Method & path | Body | Result |
|---|---|---|
| `GET /pdf/{documentId}/info` | — | `PdfInfo { pageCount, pages[{number,width,height,rotation}], encrypted, signed, activeSignatureEnvelope, outline[{title,page,level}] }` |
| `POST /pdf/merge` | `MergeRequest { sources[{documentId, pages?}], addOutline, output }` | `PdfOperationResponse` |
| `POST /pdf/split` | `SplitRequest { documentId, mode: EVERY_N_PAGES \| AT_PAGES \| PAGE_RANGES \| EVERY_PAGE \| BY_OUTLINE_LEVEL, n?, pages?, ranges?, outlineLevel?, output: { folderId?, namePattern, createSubfolder } }` | `PdfOperationResponse` (N outputs) |
| `POST /pdf/organize` | `OrganizeRequest { documentId, pages[{documentId?, page, rotation}], output }` — the generic composition; covers reorder / delete / duplicate / rotate per page / extract / insert from another PDF | `PdfOperationResponse` |
| `POST /pdf/rotate` | `RotateRequest { documentIds[], angle: 90\|180\|270, pages?, output }` — batch convenience for scans | `PdfOperationResponse` |

`OutputTarget { mode: NEW_VERSION \| NEW_DOCUMENT, folderId?, name?, allowDuplicateFileNames?, acknowledgeSignatureLoss? }`.
`PdfOperationResponse { outputs[{documentId, name, pageCount, size, versionId?}] }`.
`namePattern` supports `{name}`, `{index}`, `{first}`, `{last}` (default `{name}-{index}`).

Errors follow `GlobalExceptionHandler`: 404 (document / feature off), 403, 413 (`max-input-bytes`),
422 (`PdfToolsException`), 409 (signed without acknowledgement, active envelope, WORM). A
`PdfToolsException` message starts with its stable code so clients can match on it:
`NOT_A_PDF`, `PDF_ENCRYPTED`, `PDF_INVALID`, `PDF_TOO_MANY_PAGES`, `PDF_NO_OUTLINE`, `TOO_MANY_OUTPUTS` (422),
`PDF_SIGNED`, `ACTIVE_SIGNATURE_ENVELOPE`, `WORM_MODE` (409), `BUSY` (503). Bad page selections and
malformed requests are 400 (`IllegalArgumentException`). `OutputTarget.folderId = null` means *the folder
of the (first) source*; default names are `<first> (merged).pdf`, `<name> (edited).pdf`,
`<name> (rotated).pdf`; a split part defaults to `{name}-{index}` with a zero-padded index.

### 3.3 Access control, audit, toggle

- **Sources** are read through `documentService.findDocumentToDownloadById(id)` (`AccessType.RO`);
  never `documentRepository`. **Targets**: `uploadDocument` checks the folder (`AccessType.RW`),
  `replaceDocumentContent` checks the document (`AccessType.RWD`). The EE DAO therefore enforces
  ownership/shares with no extra code.
- **Roles**: `isInsertOrUpdateAccess` gains `/pdf/**` → CONTRIBUTOR; `GET /pdf/**/info` follows
  the standard GET rule (READER, CONTRIBUTOR). No new `ToolCapability` is needed
  (`DOCUMENT_READ` / `DOCUMENT_WRITE`), so `ToolRoleParityWithRestTest` stays green.
- **Audit**: the pipelines already log `UPLOAD_DOCUMENT` / `REPLACE_DOCUMENT_CONTENT`. Add
  `AuditAction.PDF_TRANSFORM` logged on each output with `{operation, sourceDocumentIds, pageCount}`
  so provenance ("this file was merged from A and B") is queryable.
- **Toggle**: `openfilz.pdf-tools.active` (`OPENFILZ_PDF_TOOLS_ACTIVE`, **default `true`** — the
  feature is cheap and core, unlike e-Sign), runtime-checked (`requireActive()` → 404), surfaced as
  `Settings.pdfToolsActive`. Keep `SettingsServiceImpl` changes in the protected builder path so the
  EE subclass inherits them. Use the `native-safe-feature-toggle` skill when implementing.

```yaml
openfilz:
  pdf-tools:
    active: ${OPENFILZ_PDF_TOOLS_ACTIVE:true}
    max-input-bytes: ${OPENFILZ_PDF_TOOLS_MAX_INPUT_BYTES:209715200}   # per operation, all sources
    max-pages: ${OPENFILZ_PDF_TOOLS_MAX_PAGES:2000}                     # per operation, all sources
    max-outputs: ${OPENFILZ_PDF_TOOLS_MAX_OUTPUTS:200}                  # split
    max-concurrent-operations: ${OPENFILZ_PDF_TOOLS_MAX_CONCURRENT:2}
```

### 3.4 AI assistant + MCP tools

New `service/ai/PdfAiTools` (`@Tool` methods, name-or-id resolution shared with
`DocumentAiTools.resolveDocumentToId` — extract that helper into a small package-level resolver
rather than duplicating it) and `service/mcp/PdfAiToolsContributor implements McpToolContributor`:

| Tool | Capability |
|---|---|
| `getPdfInfo(document)` | DOCUMENT_READ |
| `mergePdfs(documents, outputName, folder?)` | DOCUMENT_WRITE |
| `splitPdf(document, mode, n? / pages? / ranges?, folder?)` | DOCUMENT_WRITE |
| `rotatePdf(document, angle, pages?, asNewVersion?)` | DOCUMENT_WRITE |
| `deletePdfPages(document, pages, asNewVersion?)` | DOCUMENT_WRITE |
| `extractPdfPages(document, pages, outputName)` | DOCUMENT_WRITE |
| `reorderPdfPages(document, newOrder, asNewVersion?)` | DOCUMENT_WRITE |

Every tool goes through `AiAccessPolicy` for each source and the target (as `DocumentAiTools`
does), so EE's `CollaborationAiAccessPolicy` applies unchanged. The contributor returns no tools
when `pdf-tools.active=false`. Native: register the class in `McpRuntimeHints` next to
`DocumentAiTools`.

**Required core change so tools reach the chat, not only MCP:** today
`ChatClientAssembler.assemble(chatModel, documentAiTools)` wires a single tool object, while the
MCP provider iterates every `McpToolContributor`. Generalise the assembler and
`AiChatServiceImpl` to bind *all* contributors per request (`DocumentAiTools` stays first, for its
link registry). Side benefit: the EE share/comment tools become available in the chat too.
Update `docs/mcp.md` (tool table, "17 tools" count) and `docs/ai.md`.

### 3.5 Tests

- Unit: `PdfCompositionEngineTest` (PDFs built in-test with `PDDocument` + `PDPage`, assert page
  counts, order via per-page text, rotations, outline, encrypted/signed guards),
  `PageRangeParserTest`, `PdfAiToolsTest`.
- Integration (`e2e/pdf/`, template `AbstractSignatureIT` + `SignatureDisabledIT`):
  `PdfToolsIT` (upload → merge/split/organize/rotate through REST → download and assert with
  `Loader.loadPDF` + `PDFTextStripper`; audit entries present; version created on `NEW_VERSION`;
  roles: READER 403, CONTRIBUTOR ok), `PdfToolsDisabledIT` (404s, `Settings.pdfToolsActive=false`),
  `McpPdfToolsIT` (tools advertised, refused in `READ_ONLY`).
- Add a second small fixture (`pdf-3-pages.pdf`, known text per page) next to `pdf-example.pdf`.
- Exit criteria: `CI=true mvn clean verify` green, SDK generation compiles for all 5 SDKs.

## 4. Frontend design (openfilz-web)

### 4.1 Entry points

| Surface | Action | Condition |
|---|---|---|
| Selection bar (desktop) + bottom sheet (mobile, category `organize`) | **Merge** | ≥ 2 selected, all PDFs |
| | **Organize pages**, **Split**, **Rotate** | 1 selected PDF (Rotate also accepts several) |
| Item kebab + right-click | Organize pages / Split / Rotate | `isPdfItem(item)` |
| File viewer dialog (PDF mode) | "Edit pages" button → organizer | PDF |
| Everywhere | hidden unless `Settings.pdfToolsActive` and role CONTRIBUTOR (`PdfToolsAccessService`, copy of `signature-access.service.ts`) | |

Descriptor changes in `models/file-actions.ts`: new ids `organizePdf`, `splitPdf`, `mergePdf`,
`rotatePdf`; add two optional descriptor flags `pdfOnly?: true` and `minSelection?: number` so the
toolbar filters them the way it already handles `singleOnly`. One `case` each in
`ToolbarComponent.onAction`, `file-list`/`file-grid` `onMenuAction`, and the handler
`onPdfToolsAction(id)` lives in `FileOperationsComponent` (lazy `import()` of the dialogs so pdf.js
is not shipped twice — same reason as `onRequestSignature`).

### 4.2 New files

```
services/pdf-tools.service.ts            REST client (HttpClient, environment.apiURL) — DocumentApiService stays diff-free
services/pdf-tools-access.service.ts     flag + CONTRIBUTOR role
models/pdf-tools.models.ts               DTOs mirroring the backend
utils/pdf-page-ranges.ts                 range parser/formatter (client-side preview + validation)
components/pdf-page-grid/                reusable lazy thumbnail grid (see 4.3)
dialogs/pdf-organizer-dialog/            full-size dialog (95vw/95vh like the viewer)
dialogs/pdf-merge-dialog/
dialogs/pdf-split-dialog/
dialogs/pdf-rotate-dialog/               tiny: CW / CCW / 180°, all pages, new version by default
```

### 4.3 The page grid (`components/pdf-page-grid`)

- Input: one or more pdf.js `PDFDocumentProxy` (bytes fetched via `downloadDocument` →
  `arrayBuffer` → `getDocument({data})`, worker from `utils/pdfjs-worker.ts`).
- Lazy render with `IntersectionObserver`, one `renderTask` per tile with cancellation on scroll /
  destroy (pattern from `request-signature-dialog`), thumbnail size slider, 500-page documents
  stay smooth because only visible tiles render.
- Tile: page number, rotation applied visually (CSS transform, no re-render), hover/touch overlay
  with rotate ⟲ ⟳, delete, checkbox; selection by click / Shift / Ctrl, "Select pages" range
  field (`1-3, 7, 10-`), select all / odd / even.
- Reorder with `@angular/cdk/drag-drop` (`cdkDropListOrientation="mixed"`), keyboard: arrows to
  move focus, `R` rotate, `Delete` remove, `Ctrl+Z` / `Ctrl+Y` undo-redo (in-memory model — the
  grid never touches the server until Save).
- Theming: tokens from the EE `CLAUDE.md` table, `::ng-deep` dialog overrides, dark-theme
  `--mat-<component>-*` re-pointing for checkbox / slider / slide-toggle, `dvh` + pinned footer
  for mobile.

### 4.4 Dialogs (UX rules: one click to a sensible result, non-destructive by default, always reversible)

- **Organizer**: toolbar (undo/redo, rotate selected, delete selected, duplicate, extract selected
  → new document, insert pages from another PDF *(v1.1)*), the grid, and a pinned footer with a
  split Save button: **Save as new document** (default when signed or while an e-Sign envelope is
  running) / **Save as new version** (default otherwise; the version history makes it reversible;
  disabled when `info.activeSignatureEnvelope`). A warning banner when `info.signed` or
  `info.activeSignatureEnvelope`, and API refusals are shown in the footer — a snackbar is
  hidden behind the full-height dialog. Result: toast with "Open".
- **Merge**: ordered list of the selected PDFs (CDK vertical drag, first-page thumbnail, page
  count from `/info`, optional per-file page range), "Add bookmarks per file" toggle, output name
  (default `<first name> (merged).pdf`), destination = current folder. Result: list refresh +
  `navigateToUploadedFile` on the new document.
- **Split**: mode radio (every N pages / at selected pages — pick cut points on the grid / custom
  ranges / by bookmarks / every page), live preview line "→ 5 documents: report-1.pdf (p.1-3) …",
  naming pattern, "Create a sub-folder" toggle. Result: toast with the count, list refresh.
- Progress: sticky snackbar (`duration: undefined`) during the call, dismissed on completion.
- i18n: new `pdfTools.*` block plus `operations.pdf*` / `toolbar.pdf*` keys in **all 8 locales**;
  validate with `openfilz-web-ee/check_web_translations.py`.

### 4.5 EE follow-up (openfilz-web-ee)

After `git pull upstream main`: mirror the four descriptors in `models/file-actions-ee.ts`
(`EE_SELECTION_ACTIONS`, `EE_ITEM_ACTION_ORDER`) and extend `isItemActionAllowed` (owner or
`sharedEdit` for in-place actions, read access for merge sources). No other EE code.

## 5. Delivery plan

| Phase | Content | Repos | Estimate |
|---|---|---|---|
| 0 | This design; decide the open points in §6 | — | 0.5 d |
| 1 | Backend: engine, parser, service, 5 endpoints, toggle + `Settings`, security allow-list (+ WORM), `PDF_TRANSFORM` audit, exceptions, unit + ITs, fixture, SDK generation check | openfilz-core (`develop`) | 4–5 d |
| 2 | AI/MCP: `PdfAiTools`, contributor, chat assembler generalisation, role/MCP tests, `docs/mcp.md` + `docs/ai.md` | openfilz-core | 2 d |
| 3 | Frontend: service, page grid, organizer, merge, split, rotate, actions, viewer button, i18n ×8, theming, mobile | openfilz-web | 6–8 d |
| 4 | Integration: core submodule bump in enterprise + **native build check**, web-ee upstream pull + descriptor mirror, `demo-e2e` scenario (`quarterly-report.pdf` fixture exists), docs (`user-guide.md`, `admin-guide.md` config, `developer-guide.md`), site feature copy, release | enterprise, web-ee, demo-e2e, site | 2–3 d |

Phases 1–2 and 3 can run in parallel once the API contract in §3.2 is frozen (the frontend can
start against a mocked service). Total ≈ 3 working weeks for one developer, ≈ 2 with two.

### v2 backlog (not in scope now)
Compress (CE: PDFBox image re-encode; EE: Ghostscript via `archiving-api`), watermark / page
numbers / text stamp (bundle a Unicode font such as Noto Sans; `SignaturePdfServiceImpl` is
Standard-14 only), protect / unlock, images ↔ PDF, alternate mix, split by size, N-up / booklet,
crop, repair (EE Ghostscript), async job with progress for very large inputs, PDF → PDF/A (EE).

## 6. Open points to confirm

1. `openfilz.pdf-tools.active` default **on** (proposed) versus off like e-Sign.
2. Rotate / organize default target: **new version** (proposed, reversible through version history)
   versus always a new document. Under WORM mode only *new document* is possible.
3. Whether documents bound to an **active e-Sign envelope** should be refused for in-place edits
   (proposed: yes) — to verify against how envelopes reference their source document.
4. Split output naming default: `{name}-{index}` (proposed) versus `{name} (p.{first}-{last})`.
