# MCP Server — connecting external AI agents to OpenFilz

OpenFilz can act as an **MCP server** ([Model Context Protocol](https://modelcontextprotocol.io)),
letting agents that live *outside* OpenFilz — Claude Code, Claude Desktop, n8n, LangChain, custom
Spring AI clients — search, read and organise documents on the user's behalf.

It exposes **the same tools the built-in AI assistant uses**, over `POST /mcp`.

```
                    ┌── AiChatService ──► ChatClient   (an LLM inside OpenFilz)
DocumentAiTools ────┤
  + AiAccessPolicy  └── MCP server ─────► POST /mcp    (an LLM outside OpenFilz)
```

Two consequences worth internalising before reading on:

- **The MCP server is not a second implementation.** Any capability added to the tool layer is
  gained by the chat assistant and every external agent at once.
- **It does not need an LLM of its own.** `openfilz.ai.active` and `openfilz.mcp.active` are
  independent switches. A deployment can run the MCP server with no chat model, no embeddings and
  no pgvector at all — the calling agent brings its own model. (Only `describeImage` uses a local
  model, and it says so plainly when there isn't one.)

### Community vs Enterprise

MCP itself is **open source** and works in Community Edition out of the box — no licence, no extra
service. What differs is how it is *scoped and managed*, and that difference is the reason to run it
on Enterprise for anything multi-user:

| | Community (CE) | Enterprise (EE) |
|---|---|---|
| `/mcp` endpoint, the document tools | ✅ | ✅ |
| Per-user document scoping | **permit-all** — an agent sees *every* document | ownership + sharing enforced; an agent sees only its user's documents |
| Share / comment tools | — | ✅ |
| Scoped, revocable agent tokens | — | ✅ (`/api/v1/admin/mcp/tokens`) |
| Roles / seats | READER/CONTRIBUTOR gate | full role model; write agents are licensed seats |

> **Run CE MCP only single-user or in evaluation.** Because CE has no per-document permissions, an
> agent there can read across all documents. For any multi-user or production deployment, Enterprise
> is what makes an agent see exactly what its user may — see the security model below.

> **Related:** [AI Architecture](ai.md) for how the shared tool layer works internally ·
> [Admin guide → MCP Server](admin-guide.md#mcp-server-external-ai-agents) for the property tables ·
> [Developer guide → MCP Server](developer-guide.md#mcp-server) for the raw JSON-RPC calls.

---

## 1. Enabling it

| Property / Env Variable | Default | Description |
|---|---|---|
| `openfilz.mcp.active` / `OPENFILZ_MCP_ACTIVE` | `false` | Master switch for the MCP server |
| `openfilz.mcp.mode` / `OPENFILZ_MCP_MODE` | `READ_ONLY` | `READ_ONLY` or `READ_WRITE` — see below |

```bash
OPENFILZ_MCP_ACTIVE=true
OPENFILZ_MCP_MODE=READ_ONLY      # raise to READ_WRITE deliberately
```

The endpoint is then live at `POST /mcp` (e.g. `http://localhost:8081/mcp`).

Nothing else has to be configured. `/mcp` sits on the existing OAuth2 resource-server chain, so it
is JWT-protected from the moment it exists.

### Read-only by default, on purpose

An MCP client is an *autonomous agent* acting on a document management system. Mutating tools are
therefore withheld unless you opt in:

| Mode | Tools exposed |
|---|---|
| `READ_ONLY` (default) | every *read* tool: `whoami`, `queryDocuments`, `readDocumentContent`, `getDocumentPath`, `describeImage`, `downloadDocument`, `getDocumentActivity`, the metadata / version reads, `getPdfInfo`, `planReorganization`, `getReorganizationPlan`, the e-Sign reads |
| `READ_WRITE` | all of the above **plus** every *write* tool: `writeFile`, `createBlankDocument`, `createFolder`, `moveDocuments`, `renameDocument`, the metadata writes, `restoreVersion`, `deleteDocument`, the PDF transformations, `proposeReorganizationPlan` / `applyReorganizationPlan`, `sendForSignature` |

In `READ_ONLY` the mutating tools are absent from `tools/list` — an agent cannot choose a tool it
never saw — *and* refused if called anyway, so a client holding a cached tool list gains nothing.

---

## 2. Security model

**Every request carries its own bearer token.** The transport is *stateless*
(`spring.ai.mcp.server.protocol=STATELESS`), so no server-side MCP session can outlive the JWT that
opened it, and horizontal scaling needs no sticky sessions.

- **Identity comes from the token, never from tool arguments.** An agent cannot ask to act as
  someone else. The already-validated `Authentication` is carried onto the tool thread, and a call
  that arrives without one is refused rather than run unbound.
- **Your roles apply, unchanged.** An MCP call is authorised exactly as the equivalent REST call
  is — a READER may search and read, only a CONTRIBUTOR may write. This is not a parallel rule set:
  a test drives both the MCP capability check and the REST security chain and fails the build if
  they ever disagree.

  | Role | What it can do over MCP |
  |---|---|
  | `READER` | search and read documents |
  | `CONTRIBUTOR` | search, read **and** write (create, move, rename) |
  | `CLEANER` | delete (`deleteDocument`) |
  | `AUDITOR` | read the audit trail *(no tool yet)* |
  | `SIGN_REQUESTER` | initiate e-Sign requests (`sendForSignature`), together with `CONTRIBUTOR` — only when `openfilz.signature.require-requester-role=true`; otherwise `CONTRIBUTOR` alone suffices |
  | `VIEW_SHARE` / `EDIT_SHARE` *(Enterprise)* | read / manage shares *(no tool yet)* |
  | `COMMENTER` *(Enterprise)* | read and add comments (`listComments`, `addComment`) |

  Because `tools/list` is built once per deployment rather than per caller, a READER still *sees*
  the write tools advertised and is refused when calling one — the same behaviour as `READ_ONLY`
  mode.
- **Tools are bound to the calling user per call.** Every document the tools touch is checked
  against the same access policy the chat assistant uses. In OpenFilz Community that policy permits
  all documents (CE has no per-document permissions); in Enterprise it is backed by the real
  ownership/share model, so **an agent sees exactly what its user can see** — no extra
  configuration.
  <br>Roles and document scope are **two independent gates and both must pass**: roles decide
  *what kind* of operation is allowed, the access policy decides *which documents* it may touch.
- **Mutations stay traceable.** The audit trail records the authentic user, so MCP-driven changes
  are attributed like any other action.
- **No token, no access.** A request without a bearer token — or with an invalid one — gets `401`
  before reaching any tool.

> Give agents a **dedicated user or service account** with only the roles it needs, rather than
> reusing a human's credentials. See
> [Service Account Tokens](developer-guide.md#service-account-tokens-server-to-server).

---

## 3. The tool surface

Nineteen document tools, seven PDF tools, four reorganisation tools and four e-Sign tools, curated rather than generated. (A 60-operation auto-generated tool list from the
OpenAPI spec would make agents *worse*, not better — the small, well-described surface is the point.)

| Tool | Mode | What it does |
|---|---|---|
| `whoami` | read | The identity the session acts as (email, name) and the operations the caller's roles allow — derived from the caller's own token by the same policy that judges every call. Needs **no role** (any authenticated caller); an agent should call it before mutating operations to confirm its own blast radius. |
| `queryDocuments` | read | List folder contents, search by name, find recent files, count documents. **The main entry point** — most other tools take a *name*, and this is what resolves an ambiguous name to the right item. |
| `readDocumentContent` | read | Extract the text of a document. |
| `getDocumentPath` | read | Full path (ancestors) of a document, from root to its parent folder. |
| `describeImage` | read | Vision: describe/caption an image or PDF, OCR its text, or answer a question about it. Needs a local chat model — degrades with a clear message when there is none. |
| `writeFile` | write | Write text content to a new file. `autoFile=true` additionally lets smart filing choose its folder right after saving (needs `openfilz.ai.auto-file.active`). |
| `createBlankDocument` | write | Create an empty Word, Excel, PowerPoint or text document (the same templates as the app's "New document" menu), ready to edit. |
| `createFolder` | write | Create a folder. |
| `moveDocuments` | write | Move files or folders into another folder. |
| `renameDocument` | write | Rename a file or folder. |
| `getMetadata` | read | Read a document's metadata (custom key/value properties). |
| `searchByMetadata` | read | Find documents by their metadata. |
| `updateMetadata` | write | Add or update metadata keys on a document. |
| `deleteMetadata` | write | Remove metadata keys from a document. |
| `deleteDocument` | delete | Delete a document or folder (to the recycle bin when soft-delete is on). Requires the CLEANER role. |
| `listVersions` | read | List a document's stored versions (versioned storage). |
| `restoreVersion` | write | Restore a previous version as the current content. |
| `getDocumentActivity` | read | The audit trail of a document or folder (who did what, when), most recent first. Needs the **AUDITOR** role, like `/api/v1/audit`. |
| `downloadDocument` | read | Get a document's content — extracted text for text/PDF/Office, or a download link for binary files. |

### PDF tools

Present whenever `openfilz.pdf-tools.active` is on (the default). They act on PDFs already stored
in the DMS and write their result back as a regular document — a new document, or a new version of
the source — with an audit entry carrying the provenance (`PDF_TRANSFORM`). Page selections use the
same syntax everywhere: `1-3,7,10-`, `odd`, `even`, `all`.

| Tool | Mode | What it does |
|---|---|---|
| `getPdfInfo` | read | Page count, page sizes, bookmarks, and whether the PDF is password-protected or digitally signed. |
| `mergePdfs` | write | Merge several PDFs (in the given order) into a new document, optionally with one bookmark per source. |
| `splitPdf` | write | Split a PDF into new documents: every page, every N pages, at given pages, by page ranges, or at bookmarks. |
| `rotatePdf` | write | Rotate all or selected pages by 90/180/270° — in place (new version) or as a new document. |
| `deletePdfPages` | write | Remove pages — in place or as a new document. |
| `extractPdfPages` | write | Copy selected pages into a new document. |
| `reorderPdfPages` | write | Reorder pages — in place or as a new document. |

In-place edits are refused on digitally signed PDFs (the signature would break — the tool says so and
suggests a new document), on documents with an active e-Sign envelope, and under WORM mode.

### Reorganisation tools

Let an agent propose a new folder hierarchy for a messy folder — and let the *user* decide. The loop
is **inventory → the model proposes → the backend validates and stores the proposal → the user
confirms → apply**; the model only decides the taxonomy, everything that can go wrong is checked
deterministically against the live library and the caller's permissions.

| Tool | Mode | What it does |
|---|---|---|
| `planReorganization` | read | Inventory of a folder subtree (sub-folders, files with id / path / type / size / date / metadata) plus the JSON contract of a plan. |
| `proposeReorganizationPlan` | write | Validate the model's plan — unknown documents, documents the caller may not move, folders it may not create in, a folder moved into itself, name clashes, no-op moves — and store it as a **proposal**. Nothing moves. Returns the plan id, what is applicable and what is blocked (and why). |
| `applyReorganizationPlan` | write | Apply a proposed plan (all applicable items, or a selection): creates the missing folders, moves the documents one by one, reports what moved and what failed. |
| `getReorganizationPlan` | read | A plan's status (proposed, applied, discarded…), its moves and their outcome. |

In the OpenFilz app the assistant's proposal appears as an interactive **card** — the user ticks the
moves they want and clicks *Apply* (or *Discard*); the card calls `/api/v1/ai/reorganization/{id}/apply`.
An external agent asks its user and then calls `applyReorganizationPlan` itself. A plan can be
applied once; it is re-validated at apply time, so a document that changed in the meantime is
reported rather than moved blindly.

### e-Sign tools

Present whenever `openfilz.signature.active` is on (each call answers "disabled" otherwise). Field
placement is the part a model cannot do — an envelope needs normalised page coordinates for every
field — so `sendForSignature` takes one of two routes: an **e-Sign template** the user prepared in the
app (its fields are already placed; the agent only binds the template's roles to people), or the
**default placement**: one signature field per signer, stacked at the bottom of the last page.

| Tool | Mode | What it does |
|---|---|---|
| `sendForSignature` | write | Send a PDF for signature. Recipients as `Alice Smith <alice@x.com>, bob@y.com` (`cc:` prefix for a copy recipient); with a template, `Tenant: alice@x.com; Landlord: bob@y.com` or one recipient per role in order. Options: title, message, expiry, sequential signing, `sendNow=false` to keep a draft. Requires `CONTRIBUTOR` (+ `SIGN_REQUESTER` when the deployment demands it). |
| `listSignatureTemplates` | read | The caller's templates: name, id, roles to bind, whether a default document is attached. |
| `listSignatureEnvelopes` | read | Envelopes the caller sent (optionally by status), or with `to-sign` those awaiting the caller's own signature. |
| `getSignatureStatus` | read | Who has viewed, signed or declined, expiry, and where the signed PDF is once completed. |

Only PDFs the caller can read are accepted, the per-document e-Sign access policy of the deployment
applies (Enterprise: ownership / EDITOR share), and the invitation emails are the same as from the
app — an agent never sees a signing link.

The server also advertises usage guidance in its `initialize` response (the MCP `instructions`
field), telling the calling agent to resolve names with `queryDocuments` first and that everything
is scoped to the caller's permissions.

### Smart filing tool

`FilingAiToolsContributor` (chat + MCP, `READ_WRITE` only), on when `openfilz.ai.auto-file.active`:

| Tool | Kind | What it does |
|---|---|---|
| `fileDocuments` | write | Let OpenFilz choose the right folder for up to 10 existing documents (the neighbour vote, then the model; a document stays where it is when nothing is confident enough) and report what moved where and why. The on-demand twin of the upload-time filing. |

`queryDocuments` also accepts `category` (one of `openfilz.ai.insights.categories`) to list only
documents whose AI-derived insight carries that category.

### Enterprise tools

The Enterprise edition adds tools for its collaboration features, advertised automatically
alongside the document tools (no configuration — they appear when the enterprise API is running):

| Tool | Mode | What it does | Role needed |
|---|---|---|---|
| `shareDocument` | write | Share a document with a user by email as READER / COMMENTER / EDITOR | CONTRIBUTOR + EDIT_SHARE |
| `unshareDocument` | write | Revoke a user's access to a document | CONTRIBUTOR + EDIT_SHARE |
| `addComment` | write | Add a comment to a document | CONTRIBUTOR or COMMENTER |
| `listComments` | read | List a document's comments | READER or CONTRIBUTOR or COMMENTER |
| `getDocumentPermissions` | read | A document's permissions: owner, who it is shared with, and each grantee's access level (READER / COMMENTER / EDITOR) | the document's **owner** (no share role needed), or VIEW_SHARE / EDIT_SHARE for a document shared with the caller |

A client discovers these at runtime through `tools/list` — an agent talking to a Community
deployment simply sees fewer tools. They carry the same per-user scoping as the document tools: an
agent can only share or comment on documents its user may act on, and permissions are only
inspectable by the owner or a share-role holder the document is shared with (the same owner
bypass the GraphQL `listDocShares` query applies).

---

## 4. Connecting a client

There are **two ways a client authenticates** to `/mcp`, and which one you use decides the rest of
this section:

- **Hand the client a bearer token** — for Claude Code, n8n, Spring AI, scripts, and any client you
  configure with an `Authorization` header. You mint a token from Keycloak once (below) and give it
  to the client. This is the common path today.
- **Let the host log in via OAuth** — for Claude Desktop, claude.ai and IDE connectors, which run
  their own login and will not accept a pasted token. Nothing to mint; see
  [Remote connectors that log in for themselves](#remote-connectors-that-log-in-for-themselves-oauth-21).

The snippets below assume the API at `http://localhost:8081`; in production use your real hostname
over HTTPS (e.g. `https://openfilz-api.yourdomain.com/mcp` or `https://yourdomain-api.openfilz.com/mcp`).

> **`api.openfilz.com` is the OpenFilz demo environment, not your endpoint.** On-prem
> deployments choose their own hostname on their own DNS (e.g. `openfilz-api.yourdomain.com`);
> Cloud-Prem deployments get an assigned `yourdomain-api.openfilz.com` hostname. Either way,
> your agents connect to *your deployment's* API hostname — the snippets below use
> `openfilz-api.yourdomain.com` as a stand-in; substitute your own.

### Quick start — pick your tool

Grab a bearer token (first block below — Claude Desktop and claude.ai don't even need one), paste
the snippet for your tool, then ask it something like *"list my folders and count the PDFs"*:

<details>
<summary><b>Get a token</b> — the <code>$TOKEN</code> in the snippets below</summary>

Only the tools that paste a token need this (Claude Code, Cursor, Gemini CLI, n8n) — VS Code /
Copilot, Claude Desktop and claude.ai sign in with OAuth instead, no token to mint. `my-agent`
is a **placeholder for a service-account client you create in Keycloak first** (no such client
ships with OpenFilz):

```bash
export TOKEN=$(curl -s -X POST \
  "https://openfilz-auth.yourdomain.com/realms/openfilz/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id=my-agent -d client_secret=<your-client-secret> \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
```

Any Keycloak access token for a real user works too. The full setup — creating the
service-account client, assigning roles, and the long-lived, revocable Enterprise
[scoped agent tokens](#scoped-agent-tokens-enterprise) — is in
[Getting a bearer token](#getting-a-bearer-token) just below.

</details>

<details open>
<summary><b>Claude Code</b></summary>

```bash
claude mcp add --transport http openfilz \
  https://openfilz-api.yourdomain.com/mcp \
  --header "Authorization: Bearer $TOKEN"
```

</details>

<details>
<summary><b>Claude Desktop / claude.ai</b> — no token, OAuth login</summary>

1. **Settings → Connectors → Add custom connector**
2. Name: `OpenFilz` — URL: `https://openfilz-api.yourdomain.com/mcp`
3. Under **OAuth client**, pick **Use your own OAuth client** → Client ID `openfilz-mcp`,
   secret blank. Leave **Authentication** on *Always required*. (The other two options —
   Anthropic's hosted client metadata, and automatic registration — both fail against
   Keycloak with *"Couldn't register with …'s sign-in service"*; see
   [the DCR note](#remote-connectors-that-log-in-for-themselves-oauth-21).)
4. **Add** → **Connect** → sign in with your OpenFilz account — done

</details>

<details>
<summary><b>Cursor</b></summary>

```json
// .cursor/mcp.json
{
  "mcpServers": {
    "openfilz": {
      "url": "https://openfilz-api.yourdomain.com/mcp",
      "headers": { "Authorization": "Bearer <your-token>" }
    }
  }
}
```

</details>

<details>
<summary><b>VS Code / GitHub Copilot</b> — no token, OAuth login</summary>

1. Command Palette → **MCP: Add Server…** → **HTTP**
2. URL: `https://openfilz-api.yourdomain.com/mcp` — name it `openfilz`
3. VS Code starts the sign-in. Self-registration (DCR) is off, so it asks for an OAuth
   client: Client ID **`openfilz-mcp`**, client secret **empty**
4. Your browser opens the OpenFilz sign-in — sign in, done

Prefer a pasted token (e.g. a service-account identity instead of yours)? A static header in
`.vscode/mcp.json` works too — see [`mcp.json` (config-file hosts)](#mcpjson-config-file-hosts).

</details>

<details>
<summary><b>Gemini CLI</b></summary>

```bash
gemini mcp add --transport http openfilz \
  https://openfilz-api.yourdomain.com/mcp \
  --header "Authorization: Bearer $TOKEN"
```

</details>

<details>
<summary><b>n8n</b></summary>

**MCP Client Tool** node, connected to an **AI Agent** node:
transport `HTTP Streamable`, URL `https://openfilz-api.yourdomain.com/mcp`,
a *Header Auth* credential `Authorization: Bearer <your-token>` —
[importable workflow](../examples/mcp/n8n/).

</details>

The rest of this section covers the two auth paths in detail, plus more clients
(Spring AI, Python, MCP Inspector).

### Getting a bearer token

For the bearer-token path, mint a token from your Keycloak realm with a **service-account client**
(client-credentials grant) and export it as `$TOKEN`:

```bash
export TOKEN=$(curl -s -X POST \
  "https://openfilz-auth.yourdomain.com/realms/openfilz/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id=my-agent \
  -d client_secret=<your-client-secret> \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
```

The client must be a **dedicated user or service account** with only the roles the agent needs
(a READER-only agent can search and read but not write — see the security model above), and its
token must carry `realm_access.roles`, which the default `openfilz` realm's mappers already do.
The full walkthrough — creating the client, assigning roles, verifying the mappers — is in the
[developer guide → Service Account Tokens](developer-guide.md#service-account-tokens-server-to-server).
Tokens are short-lived (5 min by default); a long-running agent refreshes, which its MCP client
library handles, or re-mints.

### Scoped agent tokens (Enterprise)

For long-running agents, the Enterprise edition can mint a **scoped, revocable OpenFilz token**
instead of using a raw Keycloak credential. It is bound to a dedicated OpenFilz user (so audit and
document scope resolve to that user, exactly as for a human), carries a **capability cap**
(`READ_ONLY` or `READ_WRITE`), is long-lived, and can be **revoked** without touching Keycloak.

```bash
# an admin mints one; the token is returned once
curl -X POST https://openfilz-api.yourdomain.com/api/v1/admin/mcp/tokens \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"subjectEmail":"research-agent@acme.com","mode":"READ_ONLY","label":"research bot"}'
# → { "token": "…", "jti": "…", "expiresAt": "…" }
```

Give the returned `token` to the agent as its bearer credential on `/mcp`. Enable the feature with
`openfilz.mcp.tokens.enabled=true` and a `signing-secret`; list/revoke at
`GET`/`DELETE /api/v1/admin/mcp/tokens[/{jti}]` (admin only).

> **Licence note:** a scoped token binds to a real user, so the seat model applies unchanged — a
> `READ_WRITE` agent's user must be `LICENSED` (one seat); a `READ_ONLY` agent can be a `FREE` user
> (no seat). The token adds revocability and a capability cap; it is not a way around seats.

### Claude Code

```bash
claude mcp add --transport http openfilz https://openfilz-api.yourdomain.com/mcp \
  --header "Authorization: Bearer $TOKEN"
```

### `mcp.json` (config-file hosts)

For any host configured from an `mcp.json`-style file that lets you set a static header:

```json
{
  "mcpServers": {
    "openfilz": {
      "type": "http",
      "url": "https://openfilz-api.yourdomain.com/mcp",
      "headers": { "Authorization": "Bearer ${OPENFILZ_TOKEN}" }
    }
  }
}
```

> Claude Desktop, claude.ai and IDE connectors can also connect **without a pasted token** by
> logging in — see [Remote connectors](#remote-connectors-that-log-in-for-themselves-oauth-21).
> Use whichever your host supports; the static header is the simplest when it is available.

> Hosts that only speak **stdio**, or that cannot send a custom header, need a small bridge
> process in front of `/mcp`. Prefer a host that supports streamable HTTP with headers where you
> have the choice.

### Remote connectors that log in for themselves (OAuth 2.1)

Claude Desktop, claude.ai and IDE connectors do not want a pasted token — they run an OAuth login.
OpenFilz supports this out of the box: it advertises **where to authenticate** and ships a ready
Keycloak client, so a user just picks OpenFilz and signs in.

How it works — nothing for you to build:

1. The host calls `/mcp` with no token and gets `401` carrying
   `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource"`.
2. It reads that metadata (served unauthenticated) and learns the authorization server — your
   Keycloak realm — and which scopes to request (`scopes_supported`).
3. It runs an authorization-code + PKCE login against Keycloak using the pre-registered public
   client **`openfilz-mcp`**, and calls `/mcp` with the resulting token.

The token carries the user's OpenFilz roles, so everything in the security section above still
applies — an OAuth-logged-in agent is scoped exactly like any other.

**Adding a host whose callback is not pre-registered.** The `openfilz-mcp` client already lists
loopback callbacks (`http://localhost/*`, `http://127.0.0.1/*` — these cover Cursor, VS Code /
Copilot, Windsurf, Zed and most desktop tools), the IDE schemes (`vscode://`, `cursor://`, …) and
`https://claude.ai/api/mcp/auth_callback`. A **hosted** connector with a fixed HTTPS callback that
is not on that list (for example ChatGPT or Gemini connectors) needs its callback added once, in
Keycloak → Clients → `openfilz-mcp` → *Valid redirect URIs*. No OpenFilz change and no restart —
find the exact callback URL in that provider's connector documentation.

> **Dynamic Client Registration (DCR) is intentionally off — pick "Use your own OAuth client".**
> Claude's *Add custom connector* dialog offers three **OAuth client** options; only the last
> one works against a stock OpenFilz Keycloak:
>
> | Dialog option | Works? | Why |
> |---|---|---|
> | *Use Anthropic's hosted client metadata* (CIMD — the default) | ❌ | Keycloak does not support URL-based client IDs |
> | *No client ID — register one automatically* (DCR) | ❌ | DCR is deliberately off: one shared client keeps the realm clean instead of accumulating a client per connecting app |
> | ***Use your own OAuth client*** | ✅ | Client ID `openfilz-mcp`, secret blank |
>
> Leave **Authentication** on *Always required* (auto-detected). Picking either failing option
> shows *"Couldn't register with …'s sign-in service. You can try again, or add an OAuth Client
> ID in the connector settings."* — that dialog lets you switch to `openfilz-mcp` without
> recreating the connector. Cursor, VS Code, n8n and any client that accepts a `client_id` use
> the same value — VS Code handles the failed registration itself, prompting for a Client ID
> (`openfilz-mcp`) and secret (leave empty) when adding the server. DCR can be enabled in
> Keycloak later with no code change; claude.ai's hosted connector *directory* listing still
> expects DCR.

> **Upgraded deployments: the `openfilz-mcp` client must exist in your live realm.** The realm
> export ships it, but Keycloak **imports a realm only when it is first created** — a realm that
> predates OpenFilz's MCP feature does not gain the client on upgrade, and every OAuth login
> fails with *Client not found*. Add it once by hand: Keycloak admin → your realm → Clients →
> **Import client**, pasting the `openfilz-mcp` block from `realm-export.json` (public client,
> PKCE `S256`, standard flow only, the redirect URIs listed above, and `offline_access` as an
> **optional client scope**). Two quick checks that both halves are live:
> `GET /.well-known/oauth-protected-resource` on the API must answer `200` (a `404` means
> `openfilz.mcp.active` is off), and the Keycloak `…/auth?client_id=openfilz-mcp&…` page must
> show a login form, not *Client not found*.

> **Scopes: the resource metadata names them, so well-behaved hosts stay green.** The
> protected-resource metadata advertises `scopes_supported` (default `openid profile email
> offline_access`, override with `OPENFILZ_MCP_SCOPES_SUPPORTED`), and per the MCP authorization
> spec a host SHOULD request exactly those — so custom client scopes your realm defines are
> simply never requested. A host that ignores it falls back to the *realm's* `scopes_supported`
> (from the OIDC discovery document) and requests the union of everything there; Keycloak then
> refuses the whole login when *any* requested scope is not assigned to the client — the
> symptom is a successful sign-in page followed by *"Authorization with … failed"*
> (`error=invalid_scope`, Keycloak log: `Invalid scopes: …`). Two rules keep such hosts green:
> `offline_access` must be assigned to `openfilz-mcp` as an **optional client scope** (Claude
> uses it to hold a refresh token for the persistent connection — the shipped export now
> includes it), and any **custom client scope** your realm defines must either also be assigned
> to `openfilz-mcp` (optional is fine) or be deleted if unused — a leftover scope nothing
> references still appears in the realm's `scopes_supported` and breaks the login.

### n8n

Use the **MCP Client** node: transport `HTTP Streamable`, URL `https://openfilz-api.yourdomain.com/mcp`,
with an `Authorization: Bearer …` header credential.

### Spring AI

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

```yaml
spring.ai.mcp.client.streamable-http.connections.openfilz.url: https://openfilz-api.yourdomain.com/mcp
```

### Runnable examples

Working, copy-pasteable clients for each of these live in [`examples/mcp/`](../examples/mcp) — a plain Python client, a Claude-driven Python agent, a Spring AI client, an n8n workflow, and a registry `server.json`.

### Verifying by hand

The official [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is the quickest
end-to-end check:

```bash
npx -y @modelcontextprotocol/inspector --cli http://localhost:8081/mcp \
  --transport http --header "Authorization: Bearer $TOKEN" --method tools/list
```

It should list 24 tools in `READ_WRITE` mode, 10 in `READ_ONLY` (Community counts; Enterprise adds the share/comment/permissions tools). Dropping the `--header` must fail
with `401` — if it does not, `/mcp` is not protected and something is very wrong.

---

## 5. Troubleshooting

| Symptom | Cause |
|---|---|
| `401` on every call | No/invalid bearer token, or the token is not for this realm. `/mcp` is never anonymous. |
| `tools/list` returns nothing | `openfilz.mcp.active` is `false`. |
| Only the read tools listed | `READ_ONLY` mode (the default). Set `OPENFILZ_MCP_MODE=READ_WRITE`. |
| *"This OpenFilz MCP server is read-only"* | A mutating tool was called in `READ_ONLY` mode. |
| *"Not permitted: your OpenFilz role does not allow this operation"* | The user lacks the role — e.g. a READER calling `createFolder`. Grant CONTRIBUTOR, or use a `READ_ONLY` deployment. |
| *"Not authenticated: this MCP server requires a bearer token…"* | The token did not reach the tool. Check that a proxy in front of OpenFilz forwards the `Authorization` header. |
| *"Vision analysis is unavailable…"* from `describeImage` | No chat model configured. Either enable one (`openfilz.ai.active=true` + a provider) or let the calling agent analyse the file itself. |
| Agent keeps saying a document does not exist | It is scoped to the caller's permissions. In Enterprise, the document may simply not be shared with that user. |

---

## 6. Notes for operators of the Enterprise edition

- **Nothing extra to configure for security.** The enterprise access policy is picked up
  automatically, so MCP agents are constrained by the same ownership and sharing rules as the web
  UI.
- The enterprise API ships as a **GraalVM native image**, and the MCP layer is compiled into it.
  The endpoint is verified end to end against that native artifact — booted, with every tool
  driven — and not only against a JVM build, so `/mcp` behaves identically on the image you deploy.

---

## 7. Where to look in the code

| Concern | Class |
|---|---|
| Feature toggle + mode + advertised OAuth scopes | `config/McpProperties.java` |
| OAuth 2.1 discovery (RFC 9728 protected-resource metadata) | `controller/rest/McpDiscoveryController.java` |
| Transport wiring, carries the caller's identity | `config/McpConfig.java` |
| Lifts `Authentication` into the exchange | `service/mcp/McpAuthenticationWebFilter.java` |
| Per-call user binding, read-only enforcement | `service/mcp/McpToolCallbackProvider.java` |
| The tools themselves (shared with the chat assistant) | `service/ai/DocumentAiTools.java` |
| Native-image hints | `config/McpRuntimeHints.java` |
