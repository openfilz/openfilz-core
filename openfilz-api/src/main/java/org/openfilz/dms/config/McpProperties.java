package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration properties for the MCP (Model Context Protocol) server endpoint.
 * Maps to {@code openfilz.mcp.*} in application.yml.
 * <p>
 * The MCP server exposes the same {@code @Tool} methods the AI assistant uses
 * ({@code DocumentAiTools}) to <em>external</em> agents — Claude Code, Claude Desktop, n8n,
 * custom agents — over {@code POST /mcp}. It is a second front-end onto the existing tool
 * layer, never a second implementation of it.
 * <p>
 * Deliberately NOT gated on a bean condition: in GraalVM native images bean conditions are
 * evaluated at build time, so like {@link AiProperties#isActive()} the whole feature is
 * toggled at <em>runtime</em> and the beans always exist.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.mcp")
public class McpProperties {

    /**
     * Master runtime switch for the MCP server. Read at runtime (never as a bean condition)
     * so it stays toggleable in GraalVM native images. When off, {@code tools/list} advertises
     * nothing and every {@code tools/call} is refused.
     */
    private boolean active = false;

    /**
     * Which tools are exposed. An MCP client is an autonomous agent acting on a document
     * management system, so the default is deliberately read-only: mutating tools have to be
     * switched on explicitly per deployment.
     */
    private Mode mode = Mode.READ_ONLY;

    public enum Mode {
        /** Query and read tools only. */
        READ_ONLY,
        /** Query, read, and mutating tools (create, write, move, rename). */
        READ_WRITE
    }

    /**
     * Upper bound for a document served through {@code resources/read}
     * ({@code openfilz://documents/{id}} — see {@code McpDocumentResources}). The whole file is
     * base64-inlined into a single JSON-RPC response (+33% over the raw size), so this caps the
     * per-read memory and payload; larger documents are refused with a pointer to the browser
     * download endpoint. Runtime-read like every flag here, never a bean condition.
     */
    private long maxResourceSizeBytes = 10L * 1024 * 1024;

    /**
     * The OAuth 2.0 authorization server that protects this MCP endpoint — the Keycloak realm URL
     * (e.g. {@code https://auth.openfilz.com/realms/openfilz}). Advertised in the RFC 9728
     * protected-resource metadata so a remote MCP host can discover where to authenticate.
     * <p>
     * Defaults in {@code application.yml} to the same {@code KEYCLOAK_REALM_URL} the resource
     * server already validates tokens against, so a standard deployment sets nothing extra.
     */
    private String authorizationServerUrl = "http://localhost:8180/realms/openfilz";

    /**
     * The OAuth scopes advertised as {@code scopes_supported} in the RFC 9728 protected-resource
     * metadata. Without this field a well-behaved MCP host (Claude Desktop, claude.ai) falls back
     * to the authorization server's OIDC {@code scopes_supported} and requests the union of
     * <em>everything the realm advertises</em> — and Keycloak refuses the whole login with
     * {@code error=invalid_scope} as soon as any realm scope (a leftover custom client scope, an
     * unassigned {@code offline_access}) is not assigned to the {@code openfilz-mcp} client.
     * Naming the scopes here keeps the authorization request down to what the client actually
     * needs, independent of whatever else the realm defines.
     */
    private List<String> scopesSupported = List.of("openid", "profile", "email", "offline_access");

    /**
     * The Keycloak client id an MCP host should authenticate with. Purely informational for the
     * server — it is the realm that owns the client — but the hosts that cannot register
     * themselves need it typed in by hand: Keycloak has dynamic client registration disabled by
     * default, so Claude Desktop / claude.ai must be pointed at an existing client ("Use your own
     * OAuth client") instead of auto-registering. Surfaced in the settings API so a user can read
     * it off their own deployment rather than guess it.
     */
    private String clientId = "openfilz-mcp";

    public boolean isReadOnly() {
        return mode == Mode.READ_ONLY;
    }
}
