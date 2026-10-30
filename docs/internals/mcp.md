# MCP Server — internals

Contributor notes for the MCP server feature: the design decisions and mechanics behind
[the public page](../mcp.md), which describes what the feature does and how to configure it.
This page is not published on the website.

## One tool layer, two front ends

```
                    ┌── AiChatService ──► ChatClient   (an LLM inside OpenFilz)
DocumentAiTools ────┤
  + AiAccessPolicy  └── MCP server ─────► POST /mcp    (an LLM outside OpenFilz)
```

The MCP server is not a second implementation: any capability added to the tool layer is gained by
the chat assistant and every external agent at once. The MCP layer never defines a tool; it adapts
the `ToolCallback`s of the registered `McpToolContributor` beans.

## Transport and identity

* **Stateless transport** (`spring.ai.mcp.server.protocol=STATELESS`): every request carries its
  own bearer token, so no server-side MCP session can outlive the JWT that opened it, and
  horizontal scaling needs no sticky sessions.
* **Identity comes from the token, never from tool arguments.** The already-validated
  `Authentication` is carried onto the tool thread, and a call that arrives without one is refused
  rather than run unbound.
* **Roles mirror REST.** An MCP call is authorised exactly as the equivalent REST call is. This is
  not a parallel rule set: `ToolRoleParityWithRestTest` drives both the MCP capability check and the
  REST security chain and fails the build if they ever disagree.
* Because `tools/list` is built once per deployment rather than per caller, a READER still *sees*
  the write tools advertised and is refused when calling one — the same behaviour as `READ_ONLY`.
* Roles and document scope are **two independent gates and both must pass**: roles decide *what
  kind* of operation is allowed, `AiAccessPolicy` decides *which documents* it may touch (core
  default: permit-all; an extension may back it with a real permission model).

## The tool surface

Nineteen document tools, seven PDF tools, four reorganisation tools and four e-Sign tools, curated
rather than generated. A 60-operation auto-generated tool list from the OpenAPI spec would make
agents *worse*, not better — the small, well-described surface is the point. MCP Inspector lists 24
tools in `READ_WRITE` mode and 10 in `READ_ONLY` on a stock Community deployment.

| Contributor | Tools |
|---|---|
| `DocumentAiToolsContributor` | the document tools |
| `PdfAiToolsContributor` | the PDF tools |
| `OrganizeAiToolsContributor` | the reorganisation tools |
| `SignatureAiToolsContributor` | the e-Sign tools |
| `FilingAiToolsContributor` | `fileDocuments` (`READ_WRITE` only, when `openfilz.ai.auto-file.active`) |
| `EmbeddingAiToolsContributor` | `backfillEmbeddings`, `getEmbeddingBackfillStatus` |

The server also advertises usage guidance in its `initialize` response (the MCP `instructions`
field), telling the calling agent to resolve names with `queryDocuments` first and that everything
is scoped to the caller's permissions.

## Where to look in the code

| Concern | Class |
|---|---|
| Feature toggle + mode + advertised OAuth scopes | `config/McpProperties.java` |
| OAuth 2.1 discovery (RFC 9728 protected-resource metadata) | `controller/rest/McpDiscoveryController.java` |
| Transport wiring, carries the caller's identity | `config/McpConfig.java` |
| Lifts `Authentication` into the exchange | `service/mcp/McpAuthenticationWebFilter.java` |
| Per-call user binding, read-only enforcement | `service/mcp/McpToolCallbackProvider.java` |
| The tools themselves (shared with the chat assistant) | `service/ai/DocumentAiTools.java` |
| Native-image hints | `config/McpRuntimeHints.java` |
