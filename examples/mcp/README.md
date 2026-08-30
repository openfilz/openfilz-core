# OpenFilz MCP examples

Ways to connect an AI agent to an OpenFilz deployment over the [MCP](https://modelcontextprotocol.io)
server (`POST /mcp`), which exposes OpenFilz's document tools — search, read, organise, and (in
Enterprise) share and comment — scoped to the calling user's permissions.

> **MCP vs the SDKs.** MCP is for the **non-deterministic** case: an LLM decides which OpenFilz calls
> to make. For **deterministic** integration (batch imports, Camunda workers, portal backends) use
> the [`openfilz-sdk-*`](../../openfilz-sdk) clients instead — they are typed, testable, and don't
> need a model in the loop.

| Example | What it shows |
|---------|---------------|
| [`python-agent/`](python-agent) | A plain MCP client (list + call, no LLM) and a Claude-driven agent |
| [`spring-ai-client/`](spring-ai-client) | Spring AI MCP client listing the OpenFilz tools |
| [`n8n/`](n8n) | An n8n AI-Agent workflow driving OpenFilz over MCP |
| [`registry/`](registry) | A `server.json` for listing OpenFilz in the MCP registry |

## Before you start

1. **Enable MCP** on the deployment: `openfilz.mcp.active=true` (and `openfilz.mcp.mode=read-write`
   if the agent must create/modify, not just read). See [`docs/mcp.md`](../../docs/mcp.md).
2. **Get a bearer token** — a Keycloak access token for a real user, or, in Enterprise, an admin-minted
   [scoped agent token](../../docs/mcp.md#scoped-agent-tokens-enterprise). Every example authenticates
   with `Authorization: Bearer <token>`; what the agent can see and do is exactly that user's scope.
3. Point the example at your endpoint — your deployment's API hostname, e.g.
   `https://openfilz-api.yourdomain.com/mcp` (or `http://localhost:8080/mcp` for local dev).
   `api.openfilz.com` is the OpenFilz demo environment, not your endpoint.

Full reference — security model, the tool tables, OAuth discovery, Community vs Enterprise — is in
[`docs/mcp.md`](../../docs/mcp.md).
