# OpenFilz MCP — Spring AI client example

A minimal Spring Boot app that connects to OpenFilz `/mcp` via the Spring AI MCP client and prints
the advertised tools. In a real app you hand `ToolCallbackProvider.getToolCallbacks()` to a
`ChatClient` so the model can call them.

> For **deterministic** integration (batch imports, Camunda workers, portal backends) use the
> `openfilz-sdk-*` clients instead. MCP is for the **non-deterministic** case where an LLM picks the
> call.

```bash
export OPENFILZ_MCP_URL="https://api.openfilz.com/mcp"
export OPENFILZ_TOKEN="<bearer token>"
mvn spring-boot:run
```

## Adding the bearer token

Spring AI builds the streamable-HTTP transport from the auto-configured reactive
`WebClient.Builder`, so a `WebClientCustomizer` that adds a default `Authorization` header is the
seam. Add this bean (this console app talks only to OpenFilz, so a global default header is fine):

```java
// NOTE: the WebClientCustomizer import is Spring-Boot-version-specific — in Boot 4 the reactive
// WebClient support lives in the spring-boot-webclient module. Adjust the import to your version.
@Component
class OpenFilzAuthCustomizer implements WebClientCustomizer {
    private final String token;
    OpenFilzAuthCustomizer(@Value("${openfilz.mcp.token:}") String token) { this.token = token; }
    @Override public void customize(WebClient.Builder builder) {
        if (token != null && !token.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }
}
```

For a host that logs in via **OAuth** rather than a static token, don't add a header — let the host
run the OAuth flow against the metadata OpenFilz advertises (see
[OAuth discovery](../../../docs/mcp.md#remote-connectors-that-log-in-for-themselves-oauth-21)).
