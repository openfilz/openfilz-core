package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
     * The OAuth 2.0 authorization server that protects this MCP endpoint — the Keycloak realm URL
     * (e.g. {@code https://auth.openfilz.com/realms/openfilz}). Advertised in the RFC 9728
     * protected-resource metadata so a remote MCP host can discover where to authenticate.
     * <p>
     * Defaults in {@code application.yml} to the same {@code KEYCLOAK_REALM_URL} the resource
     * server already validates tokens against, so a standard deployment sets nothing extra.
     */
    private String authorizationServerUrl = "http://localhost:8180/realms/openfilz";

    public boolean isReadOnly() {
        return mode == Mode.READ_ONLY;
    }
}
