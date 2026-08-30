"""
LLM-driven OpenFilz agent: Claude picks which OpenFilz tools to call to answer a prompt.

This is the real MCP use case — a non-deterministic consumer where the model chooses the calls.
It runs a small tool-use loop: hand Claude the OpenFilz tools, let it call them, feed results back.

    export OPENFILZ_MCP_URL="https://openfilz-api.yourdomain.com/mcp"
    export OPENFILZ_TOKEN="<bearer token>"
    export ANTHROPIC_API_KEY="<your Anthropic key>"
    python agent.py "Find my documents about invoices and tell me how many there are."
"""
import asyncio
import os
import sys

from anthropic import Anthropic
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

MODEL = "claude-sonnet-5"


def to_anthropic_tools(mcp_tools):
    """Map MCP tool definitions to the Anthropic tool schema."""
    return [
        {"name": t.name, "description": t.description, "input_schema": t.inputSchema}
        for t in mcp_tools
    ]


async def run(prompt: str) -> None:
    url = os.environ["OPENFILZ_MCP_URL"]
    headers = {"Authorization": f"Bearer {os.environ['OPENFILZ_TOKEN']}"}
    anthropic = Anthropic()  # reads ANTHROPIC_API_KEY

    async with streamablehttp_client(url, headers=headers) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()
            tools = to_anthropic_tools((await session.list_tools()).tools)

            messages = [{"role": "user", "content": prompt}]
            while True:
                response = anthropic.messages.create(
                    model=MODEL, max_tokens=1024, tools=tools, messages=messages
                )
                messages.append({"role": "assistant", "content": response.content})

                tool_uses = [b for b in response.content if b.type == "tool_use"]
                if not tool_uses:
                    # No more tool calls — print Claude's final answer.
                    for block in response.content:
                        if block.type == "text":
                            print(block.text)
                    return

                tool_results = []
                for call in tool_uses:
                    print(f"[tool] {call.name}({call.input})")
                    result = await session.call_tool(call.name, call.input)
                    text = "".join(b.text for b in result.content if b.type == "text")
                    tool_results.append(
                        {"type": "tool_result", "tool_use_id": call.id, "content": text}
                    )
                messages.append({"role": "user", "content": tool_results})


if __name__ == "__main__":
    question = sys.argv[1] if len(sys.argv) > 1 else "What documents do I have?"
    asyncio.run(run(question))
