# MCP connector: fix plan

Status: plan, nothing built. Written 2026-11-05 after updating a spreadsheet through the MCP
connector against a live deployment. All causes below were checked in the code; problems A, B and C
were also observed on that deployment.

## 1. Summary

| # | Problem | Cause | Severity | Effort |
|---|---------|-------|----------|--------|
| A | A read tool can answer about the wrong document, with no warning | `resolveDocumentToId` takes the first partial name match | High: wrong answer that looks right | 1 day |
| B | An editor save silently overwrites newer content | The OnlyOffice callback never compares the session with the current content | High: the newer content disappears from the current version | 2 to 3 days |
| C | A long document cannot be read through the tools | Text cut at 8 000 characters, with no way to continue | Medium | 1 day |
| D | An agent cannot replace or upload a binary file | `writeFile` takes text and creates a new file; no tool wraps `replaceDocumentContent` | Medium | 2 to 3 days |

Order: A, then B, then C, then D. D reuses the resolver of A and must not ship before B: more
writes from agents would hit the lost update of B more often.

Only A, C and D were first seen as connector problems. B lies in the editor callback, and is here
because it destroyed the work of an agent and because D's precondition depends on it.

## 2. Findings

### 2.1 What looked like a versions bug is not one

`listVersions` looked incomplete: it stopped two months back and did not show a replacement just
made. Called with the document id, it returned every version, newest first. `listFileVersions` and
`DocumentVersionServiceImpl.listVersions` are correct.

Called with the name, the tool had reported on **another document of the same name**, in another
folder. That is problem A.

### 2.2 A: silent choice between documents (`DocumentAiTools`)

```java
// resolveDocumentToId, used by the read tools
var found = documentRepository.findTop50ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(nameOrId);
return found.stream().map(Document::getId).filter(this::canRead).findFirst().orElse(null);
```

- Same name twice: the order between them is not defined, and the answer does not say which one
  was used.
- A partial name can beat an exact one: `report` matches `annual report 2019.pdf` before `report`.
- `listVersions` prints the name the caller typed, so the output hides the mistake.
- `queryDocuments` prints neither id nor folder, so the caller cannot tell two results apart or
  ask for the right one.
- `resolveForMutation` already does this properly for writes: exact name, or an error listing the
  candidates.

### 2.3 B: lost update in the editor callback (`AbstractOnlyOfficeService`)

`handleCallback` logs `callback.key()` and never uses it. `saveDocumentFromCallback` then calls
`replaceDocumentContent` unconditionally.

Reproduced from the audit trail of one document:

| Time (UTC) | Event | Size |
|---|---|---|
| 23:02:42 | Document opened in the editor | 50 KB |
| 23:18:22 | Replaced through the web app, new content | 62 KB |
| 23:19:03 | Editor callback saves the session opened at 23:02 | 50 KB |

The replacement stays in the version history but is no longer current. Nobody is told: not the
person who replaced the file, nor the person who was editing.

The same happens with any write during an open editor session: web replace, restore of a version,
PDF tool saved as a new version.

### 2.4 C: truncation (`DocumentAiTools.MAX_CONTENT_CHARS`)

`readDocumentContent` and the text part of `downloadDocument` stop at 8 000 characters. The marker
gives neither the total length nor a way to continue. On a 10-sheet workbook, the text ended in
the middle of the second sheet.

`resources/read` returns the bytes, but only for clients that implement it, and only up to
`openfilz.mcp.max-resource-size-bytes`.

### 2.5 D: no binary write

`writeFile` writes text to a new file. Replacing a spreadsheet had to go through a browser.

## 3. Fixes

### A. One resolver, no silent choice

1. New `resolveForRead(nameOrId)` returning a target or an error, the same shape as
   `MutationTarget`:
   - id: that document;
   - the turn's document registry: that document;
   - exact name (case-insensitive), one visible match: that document;
   - exact name, several visible matches: error listing id, parent folder and last update,
     asking for the id;
   - no exact name, partial matches: one visible match is used, and the answer names it; several
     give the same error as above.
2. Use it in the four callers of `resolveDocumentToId` and in the separate lookup of
   `readDocumentContent` (its `folderName` argument keeps narrowing the search), then remove
   `resolveDocumentToId`. `fetchDownload` follows.
3. Answers state what they are about. `listVersions` prints the id and folder of the document, and
   marks the current version (`DocumentVersionInfo.latest` exists and is not printed).
4. `queryDocuments` prints the id and the parent folder of each result.
5. Share the candidates message with `resolveForMutation`.

Tests: `DocumentAiToolsMcpIT` with two documents of the same name in two folders (every read tool
answers with the error, then succeeds with the id), exact beats partial, one partial match still
works and is named. The existing "resolution by partial name" cases stay green.

Watch: the chat depends on these answers. Run `AiRealLlmE2EIT` three times, as for any change of
tool wording.

### B. An editor save never overwrites what it did not start from

1. Record each editor session when the config is built: document id, key, and the base it started
   from (latest version id on versioned storage, otherwise `updated_at`). New table through Flyway,
   numbered above the highest released migration.
2. On a save callback, compare the current content with the session's base:
   - same: save as today, and move the session's base to the new version, so a second save of the
     same session (force save, then close) is not a conflict;
   - different: leave the current content alone, and save the editor's result as a new document
     next to it, named `<name> (editor copy <date>).<ext>`. Write an audit entry, and tell the
     editing user through a seam with a no-op default.
   - no session row (opened before the deployment): save as today, and log a warning.
3. Show the editing user that the document changed underneath them, when the status callback allows
   it. Optional, not needed to close the bug.
4. Delete session rows on a schedule, once closed or older than a few days.

Why a copy rather than a refusal: the editor's work must survive too. Neither side is lost, and a
person decides.

Decision to take: in WORM mode, or if the folder refuses the new document, the copy fails. Proposal:
log and audit the failure, and keep the current content.

Tests: `OnlyOfficeIT`, posting callbacks directly, no browser:
replace during a session gives a copy and leaves the current content; force save then close gives
one version each and no copy; two users in one session give no copy; local storage without versions
behaves the same.

### C. Read in pages

1. `readDocumentContent` gets two optional parameters: `offset` (characters, default 0) and
   `maxChars` (default 8 000, capped). Mark both `@ToolParam(required = false)`.
2. The answer carries a range line and the next offset:
   `[characters 8000 to 16000 of 41250, call again with offset=16000]`.
3. The cap is `openfilz.mcp.max-text-chars`, default 50 000. It is a new variable: follow the wiring
   checklist in `CLAUDE.md`, section 11, and run `deploy/check-env-wiring.py`. The chat keeps the
   8 000 default.
4. Without full-text search, each page parses the file again. Keep the last extracted text in a small
   Caffeine cache keyed on document id and `updated_at`, after the access check, short expiry and a
   limit on total size. The access check runs on every call, cached or not.
5. The `downloadDocument` text fallback stays at 8 000 and its marker names `readDocumentContent`.
6. To check: whether the Tika reader has its own write limit, which would make the total length wrong.

Tests: `DocumentAiToolsMcpIT` reads a long document in pages and rebuilds it exactly, out-of-range
offset, cap applied, a page of a document the caller lost access to is refused even when cached.

### D. Binary write tools

New contributor, `McpFileTransferToolsContributor`. **Not exposed to the chat**
(`exposeInChat() = false`): base64 has no place in a model conversation, and the small-model suite
stays on its measured tool surface.

| Tool | Parameters | Goes through |
|---|---|---|
| `replaceDocumentContent` | `document` (exact name or id), `contentBase64`, `expectedVersionId` (optional) | `DocumentService.replaceDocumentContent` |
| `uploadFile` | `fileName`, `contentBase64`, `folderName` (optional) | `DocumentService.uploadDocument` |

1. Capability `DOCUMENT_WRITE`: withheld in `READ_ONLY` mode, refused to READER, refused in WORM mode
   by `DefaultAiToolRolePolicy` for the replacement.
2. Target through `resolveForMutation`, plus `AiAccessPolicy` on the document.
3. Decode to a temp file, then `PathFilePart`, as `writeFile` does. Audit, versioning, checksum, quota,
   thumbnails and indexing then apply with nothing else to do.
4. `expectedVersionId`: if given and different from the current version, refuse and name the current
   one. `listVersions` provides the value. This is the optimistic lock that problem B lacks on the
   agent side. On storage without versions, compare `updated_at` instead.
5. Size: refuse above `openfilz.mcp.max-resource-size-bytes` (10 MB), before decoding, with a clear
   message. No new variable.
6. The answer gives the document id, the new version id and the size.

**Check first, it can block the design:** a 10 MB file is about 13.4 MB of JSON. The WebFlux
in-memory codec limit applies to the `/mcp` request body, and Spring Boot 4 ignores `spring.codec.*`
(it caused a 413 on another service). Start with a test sending 10 MB. Raise the limit for `/mcp`
only, and refuse oversized bodies before they are buffered.

Later, if files above 10 MB matter: a signed upload link, the counterpart of
`openfilz.download-tokens.*`. It is a new public write path, so it needs its own security review.
Not in this plan.

Tests: `McpProtocolIT` (expected set and `argumentsFor`), `McpReadOnlyModeIT`, `McpWithChatModelIT`,
role refusal in `McpRoleEnforcementIT`, a round trip that compares bytes, a wrong `expectedVersionId`,
quota refusal, WORM refusal, size refusal, 10 MB through the real endpoint. The tool lists of
downstream builds change too (`grep -rl FilingAiToolsContributor`, as `CLAUDE.md` explains).
`docs/mcp.md` gets the new tools and the paging parameters.

## 4. Delivery

One pull request per letter, in order, each to `develop`.

| PR | Contains | Done when |
|---|---|---|
| 1 (A) | Resolver, clearer answers | Two documents of one name can no longer be confused by a read tool |
| 2 (B) | Session table, conflict copy | The sequence of section 2.3 ends with both contents available and the newer one current |
| 3 (C) | Paging | A workbook is read to its end through the tools |
| 4 (D) | Two tools | An agent replaces a spreadsheet with no browser, and a stale `expectedVersionId` is refused |

## 5. Risks

- **Stricter answers (A):** an agent that relied on the first match now gets an error. Intended, and
  the error tells it what to do.
- **Chat with small models (A, C):** wording and schema changes can disturb tool selection. Hence the
  three runs of `AiRealLlmE2EIT`.
- **Migration (B):** do not slot the Flyway file into a gap below the released maximum.
- **Native image (B, D):** new DTOs and the new contributor need the hints check; use runtime toggles,
  no `@ConditionalOnProperty`.
- **Memory (C, D):** base64 and extracted text live in the heap. Keep the limits, and check the size
  before decoding.
