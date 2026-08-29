# OpenFilz MCP — n8n

Drive OpenFilz from an n8n workflow using the **MCP Client Tool** node connected to an **AI Agent**.

## Set it up in the n8n UI (robust across versions)

1. Add an **AI Agent** node (with a Chat Model of your choice).
2. Add an **MCP Client Tool** node and connect it to the agent's *Tool* input.
3. In the MCP Client node:
   - **Endpoint / Transport:** `HTTP Streamable`
   - **URL:** `https://api.openfilz.com/mcp`
   - **Authentication:** a *Header Auth* credential — name `Authorization`, value `Bearer <token>`
     (a Keycloak access token, or an OpenFilz [scoped agent token](../../../docs/mcp.md#scoped-agent-tokens-enterprise)).
4. Prompt the agent, e.g. *"List my folders and count the PDFs."* — it will call the OpenFilz tools.

`openfilz-mcp-workflow.json` is an importable starting point (Workflows → Import from File). Node
internals vary between n8n versions, so after importing, open the MCP Client node and confirm the
URL + Header Auth credential above.
