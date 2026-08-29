package org.openfilz.dms.config;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.mcp.McpAuthenticationWebFilter;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the MCP server transport so that MCP tool calls know who is calling.
 * <p>
 * Spring AI auto-configures a {@link WebFluxStatelessServerTransport} whose default
 * {@code contextExtractor} returns {@link McpTransportContext#EMPTY} — usable for anonymous
 * tool servers, useless for a document management system. This replaces it (the auto-configured
 * bean is {@code @ConditionalOnMissingBean}) with one that forwards the caller's already
 * validated {@code Authentication} into the transport context, where
 * {@code McpToolCallbackProvider} picks it up.
 * <p>
 * <b>Stateless, not streamable-with-session, by design:</b> every request carries its own
 * bearer token, so no server-side MCP session can outlive the JWT that opened it, and
 * horizontal scaling needs no sticky sessions. (SSE is deprecated since Spring AI 2.0.0.)
 * <p>
 * No security configuration is needed for {@code /mcp}: {@code DefaultAuthSecurityConfig} ends
 * with {@code anyExchange().authenticated()} on the OAuth2 resource server, so the endpoint is
 * JWT-protected the moment it exists. Keeping it out of the whitelist is the whole story.
 */
@Slf4j
@Configuration
public class McpConfig {

    @Bean
    public WebFluxStatelessServerTransport webFluxStatelessServerTransport(
            ObjectProvider<JsonMapper> jsonMapperProvider,
            McpServerStreamableHttpProperties streamableHttpProperties) {

        // Reuse the application's Jackson 3 mapper when there is one — that is exactly what the
        // auto-configuration does (its own mcpServerJsonMapper bean is @ConditionalOnMissingBean).
        McpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(
                jsonMapperProvider.getIfAvailable(() -> JsonMapper.builder().build()));

        log.info("MCP server transport listening on {}", streamableHttpProperties.getMcpEndpoint());

        return WebFluxStatelessServerTransport.builder()
                .jsonMapper(mcpJsonMapper)
                .messageEndpoint(streamableHttpProperties.getMcpEndpoint())
                .contextExtractor(request -> {
                    Object authentication = request.attributes()
                            .get(McpAuthenticationWebFilter.AUTHENTICATION_ATTRIBUTE);
                    return authentication == null
                            ? McpTransportContext.EMPTY
                            : McpTransportContext.create(Map.of(
                                    McpAuthenticationWebFilter.AUTHENTICATION_ATTRIBUTE, authentication));
                })
                .build();
    }
}
