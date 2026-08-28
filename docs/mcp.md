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
| `READ_ONLY` (default) | `queryDocuments`, `readDocumentContent`, `getDocumentPath`, `describeImage` |
| `READ_WRITE` | the four above **plus** `writeFile`, `createFolder`, `moveDocuments`, `renameDocument` |

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
- **Tools are bound to the calling user per call.** Every document the tools touch is checked
  against the same access policy the chat assistant uses. In OpenFilz Community that policy permits
  all documents (CE has no per-document permissions); in Enterprise it is backed by the real
  ownership/share model, so **an agent sees exactly what its user can see** — no extra
  configuration.
- **Mutations stay traceable.** The audit trail records the authentic user, so MCP-driven changes
  are attributed like any other action.
- **No token, no access.** A request without a bearer token — or with an invalid one — gets `401`
  before reaching any tool.

> Give agents a **dedicated user or service account** with only the roles it needs, rather than
> reusing a human's credentials. See
> [Service Account Tokens](developer-guide.md#service-account-tokens-server-to-server).

---

## 3. The tool surface

Eight tools, curated rather than generated. (A 60-operation auto-generated tool list from the
OpenAPI spec would make agents *worse*, not better — the small, well-described surface is the point.)

| Tool | Mode | What it does |
|---|---|---|
| `queryDocuments` | read | List folder contents, search by name, find recent files, count documents. **The main entry point** — most other tools take a *name*, and this is what resolves an ambiguous name to the right item. |
| `readDocumentContent` | read | Extract the text of a document. |
| `getDocumentPath` | read | Full path (ancestors) of a document, from root to its parent folder. |
| `describeImage` | read | Vision: describe/caption an image or PDF, OCR its text, or answer a question about it. Needs a local chat model — degrades with a clear message when there is none. |
| `writeFile` | write | Write text content to a new file. |
| `createFolder` | write | Create a folder. |
| `moveDocuments` | write | Move files or folders into another folder. |
| `renameDocument` | write | Rename a file or folder. |

The server also advertises usage guidance in its `initialize` response (the MCP `instructions`
field), telling the calling agent to resolve names with `queryDocuments` first and that everything
is scoped to the caller's permissions.

---

## 4. Connecting a client

All examples assume a Keycloak access token in `$TOKEN` and the API at `http://localhost:8081`.
In production use your real hostname over HTTPS (e.g. `https://api.openfilz.com/mcp`).

### Claude Code

```bash
claude mcp add --transport http openfilz https://api.openfilz.com/mcp \
  --header "Authorization: Bearer $TOKEN"
```

### `mcp.json` (Claude Desktop, Cursor, and other config-file hosts)

```json
{
  "mcpServers": {
    "openfilz": {
      "type": "http",
      "url": "https://api.openfilz.com/mcp",
      "headers": { "Authorization": "Bearer ${OPENFILZ_TOKEN}" }
    }
  }
}
```

> Hosts that only speak **stdio**, or that cannot send a custom header, need a small bridge
> process in front of `/mcp`. Prefer a host that supports streamable HTTP with headers where you
> have the choice.

### n8n

Use the **MCP Client** node: transport `HTTP Streamable`, URL `https://api.openfilz.com/mcp`,
with an `Authorization: Bearer …` header credential.

### Spring AI

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

```yaml
spring.ai.mcp.client.streamable-http.connections.openfilz.url: https://api.openfilz.com/mcp
```

### Verifying by hand

The official [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is the quickest
end-to-end check:

```bash
npx -y @modelcontextprotocol/inspector --cli http://localhost:8081/mcp \
  --transport http --header "Authorization: Bearer $TOKEN" --method tools/list
```

It should list 8 tools in `READ_WRITE` mode, 4 in `READ_ONLY`. Dropping the `--header` must fail
with `401` — if it does not, `/mcp` is not protected and something is very wrong.

---

## 5. Troubleshooting

| Symptom | Cause |
|---|---|
| `401` on every call | No/invalid bearer token, or the token is not for this realm. `/mcp` is never anonymous. |
| `tools/list` returns nothing | `openfilz.mcp.active` is `false`. |
| Only 4 tools listed | `READ_ONLY` mode (the default). Set `OPENFILZ_MCP_MODE=READ_WRITE`. |
| *"This OpenFilz MCP server is read-only"* | A mutating tool was called in `READ_ONLY` mode. |
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
| Feature toggle + mode | `config/McpProperties.java` |
| Transport wiring, carries the caller's identity | `config/McpConfig.java` |
| Lifts `Authentication` into the exchange | `service/mcp/McpAuthenticationWebFilter.java` |
| Per-call user binding, read-only enforcement | `service/mcp/McpToolCallbackProvider.java` |
| The tools themselves (shared with the chat assistant) | `service/ai/DocumentAiTools.java` |
| Native-image hints | `config/McpRuntimeHints.java` |
