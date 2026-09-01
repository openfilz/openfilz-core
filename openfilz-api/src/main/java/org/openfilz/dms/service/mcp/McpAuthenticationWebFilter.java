package org.openfilz.dms.service.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Lifts the authenticated {@link Authentication} out of the reactive security context and
 * parks it in the exchange attributes for MCP requests.
 * <p>
 * Why this exists: MCP tool execution happens inside the MCP server's own call stack, where
 * the tool receives an {@code McpTransportContext} but no Reactor context — so
 * {@link ReactiveSecurityContextHolder} is unreachable from a tool. The transport's
 * {@code contextExtractor} runs synchronously against a {@code ServerRequest} and therefore
 * cannot subscribe to the security context either. Exchange attributes are the one place both
 * sides can see: {@code ServerRequest.attributes()} is backed by
 * {@link ServerWebExchange#getAttributes()}.
 * <p>
 * Spring Security has already validated the bearer token by the time this filter runs
 * (everything outside the whitelist is {@code authenticated()}), so no token is re-decoded
 * here — the authentic {@code Authentication} instance is carried through, never a
 * user-supplied string.
 *
 * @see McpToolCallbackProvider
 */
@Component
public class McpAuthenticationWebFilter implements WebFilter, Ordered {

    /** Exchange attribute holding the caller's {@link Authentication}. */
    public static final String AUTHENTICATION_ATTRIBUTE =
            McpAuthenticationWebFilter.class.getName() + ".AUTHENTICATION";

    /** Path prefix this filter reacts to — matches the MCP endpoint. */
    static final String MCP_PATH_PREFIX = "/mcp";

    /**
     * The caller's {@link Authentication} as forwarded into the MCP transport context by
     * {@code McpConfig}'s contextExtractor, or {@code null} when the request carried none.
     * Shared by the tool and resource front-ends so both fail closed identically.
     */
    public static Authentication authenticationFrom(McpTransportContext context) {
        return context != null
                && context.get(AUTHENTICATION_ATTRIBUTE) instanceof Authentication authentication
                ? authentication : null;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(MCP_PATH_PREFIX)) {
            return chain.filter(exchange);
        }
        // Mono.then() plays the downstream chain on completion of the upstream — including an
        // empty completion — so an unauthenticated request still proceeds (and is rejected by
        // the security filter chain, not silently swallowed here).
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .doOnNext(authentication -> exchange.getAttributes().put(AUTHENTICATION_ATTRIBUTE, authentication))
                .then(chain.filter(exchange));
    }

    /**
     * Runs late, after Spring Security's authentication/authorization filters have populated
     * the security context. {@code LOWEST_PRECEDENCE} is safe because the MCP handler itself
     * is a routing function, which runs after every {@link WebFilter}.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
