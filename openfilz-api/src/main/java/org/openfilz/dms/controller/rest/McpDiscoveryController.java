package org.openfilz.dms.controller.rest;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.McpProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * OAuth 2.1 discovery for the MCP endpoint, so a remote host can find out how to authenticate
 * without being handed a token out of band (RFC 9728 + the MCP authorization spec).
 * <p>
 * Two documents, both readable without a token (they are whitelisted in the security config):
 * <ul>
 *   <li><b>{@code /.well-known/oauth-protected-resource}</b> — the one OpenFilz actually owns.
 *       Names this {@code /mcp} endpoint as the protected resource and points at the Keycloak
 *       realm as its authorization server. A host reads this after a {@code 401} from {@code /mcp}
 *       (whose {@code WWW-Authenticate} header carries this document's URL) and then talks to
 *       Keycloak directly.</li>
 *   <li><b>{@code /.well-known/oauth-authorization-server}</b> — a convenience redirect. The
 *       authorization-server metadata is Keycloak's to serve, not ours; a host that looks for it
 *       at the resource server is forwarded to the realm's OIDC discovery document rather than us
 *       duplicating (and risking drift from) Keycloak's own metadata.</li>
 * </ul>
 * Both answer {@code 404} when the MCP server is switched off ({@code openfilz.mcp.active=false}),
 * so a deployment that does not run MCP advertises nothing. The check is at request time, never a
 * bean condition — the whole MCP feature is a runtime toggle for GraalVM-native-image safety.
 */
@RestController
@RequiredArgsConstructor
public class McpDiscoveryController {

    private final McpProperties mcpProperties;
    private final CommonProperties commonProperties;

    /**
     * RFC 9728 protected-resource metadata. Kept deliberately minimal — {@code resource} and
     * {@code authorization_servers} are the only fields an MCP host requires; {@code
     * bearer_methods_supported} states that the token goes in the {@code Authorization} header,
     * which is how the stateless transport reads it.
     */
    @GetMapping("/.well-known/oauth-protected-resource")
    public ResponseEntity<Map<String, Object>> protectedResourceMetadata() {
        if (!mcpProperties.isActive()) {
            return ResponseEntity.notFound().build();
        }
        String resource = trimTrailingSlash(commonProperties.getApiPublicBaseUrl()) + "/mcp";
        return ResponseEntity.ok(Map.of(
                "resource", resource,
                "authorization_servers", List.of(mcpProperties.getAuthorizationServerUrl()),
                "bearer_methods_supported", List.of("header")));
    }

    /**
     * Forward to Keycloak's own authorization-server metadata. Keycloak serves the realm's
     * discovery document at {@code <realm>/.well-known/openid-configuration}; a 302 there keeps a
     * single source of truth for endpoints, supported grants, PKCE methods and (where enabled) the
     * registration endpoint.
     */
    @GetMapping("/.well-known/oauth-authorization-server")
    public ResponseEntity<Void> authorizationServerMetadata() {
        if (!mcpProperties.isActive()) {
            return ResponseEntity.notFound().build();
        }
        URI location = URI.create(trimTrailingSlash(mcpProperties.getAuthorizationServerUrl())
                + "/.well-known/openid-configuration");
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
