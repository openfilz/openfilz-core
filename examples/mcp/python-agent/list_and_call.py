"""
Minimal OpenFilz MCP client: connect, list the tools, call one.

Proves connectivity and the auth header end to end — no LLM involved. Run this first to confirm
your OpenFilz `/mcp` endpoint and bearer token work before wiring an agent.

    export OPENFILZ_MCP_URL="https://api.openfilz.com/mcp"
    export OPENFILZ_TOKEN="<a Keycloak access token, or an OpenFilz scoped agent token>"
    python list_and_call.py
"""
import asyncio
import os

from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client


async def main() -> None:
    url = os.environ["OPENFILZ_MCP_URL"]
    token = os.environ["OPENFILZ_TOKEN"]
    headers = {"Authorization": f"Bearer {token}"}

    async with streamablehttp_client(url, headers=headers) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()

            tools = await session.list_tools()
            print(f"OpenFilz advertises {len(tools.tools)} tools:")
            for tool in tools.tools:
                print(f"  - {tool.name}: {tool.description.splitlines()[0]}")

            # A read that works with any account: list the 5 most-recently-updated items.
            result = await session.call_tool(
                "queryDocuments",
                {"sortBy": "updatedAt", "sortOrder": "DESC", "pageSize": 5, "countOnly": False},
            )
            print("\nqueryDocuments result:")
            for block in result.content:
                if block.type == "text":
                    print(block.text)


if __name__ == "__main__":
    asyncio.run(main())
