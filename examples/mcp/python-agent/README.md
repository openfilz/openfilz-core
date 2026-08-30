# OpenFilz MCP — Python examples

Two scripts, both connecting to your OpenFilz `/mcp` endpoint over streamable HTTP with a bearer
token (a Keycloak access token, or an Enterprise [scoped agent token](../../../docs/mcp.md#scoped-agent-tokens-enterprise)).

```bash
pip install -r requirements.txt
export OPENFILZ_MCP_URL="https://openfilz-api.yourdomain.com/mcp"
export OPENFILZ_TOKEN="<bearer token>"
```

- **`list_and_call.py`** — connect, list the tools, run one read. No LLM. Run this first to confirm
  connectivity and auth.
- **`agent.py`** — Claude drives the OpenFilz tools to answer a prompt (needs `ANTHROPIC_API_KEY`):

  ```bash
  python agent.py "Find my documents about invoices and count them."
  ```

Everything the agent can see and do is scoped to the token's user — in Enterprise, to that user's
own and shared documents; in Community, to all documents (there is no per-user scoping).
