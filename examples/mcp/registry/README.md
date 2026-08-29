# OpenFilz MCP — registry listing

`server.json` is the entry for the official [MCP registry](https://github.com/modelcontextprotocol/registry).

**Two honest caveats before submitting:**

1. **OpenFilz is self-hosted.** There is no single public `/mcp` URL — every customer runs their own.
   The `remotes[].url` in `server.json` is a placeholder (`api.openfilz.com`); the registry entry's
   real job is discoverability of the *software* and how to connect, not a live shared endpoint.
   Consider this listing marketing/discovery, and point users at [the docs](../../../docs/mcp.md)
   for connecting their own instance.
2. **Validate against the current schema at submission time.** The registry's `server.json` schema
   evolves; the `$schema` URL and field shapes here reflect a snapshot. Run the registry's publisher
   tool / validator before submitting and adjust as needed — do not assume this file is current.

Publishing is done with the registry's CLI (`mcp-publisher`) per its README; it authenticates the
namespace (`com.openfilz/*`) via the repository you own.
