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
import org.springframework.core.codec.StringDecoder;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.DecoderHttpMessageReader;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
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

    /** Room for the JSON-RPC envelope and the other arguments around a base64 file. */
    private static final long ENVELOPE_BYTES = 64 * 1024;

    /** The largest {@code /mcp} body: a file of {@code openfilz.mcp.max-resource-size-bytes} as base64, and its envelope. */
    static long maxRequestBytes(long maxResourceSizeBytes) {
        return maxResourceSizeBytes / 3 * 4 + 4 + ENVELOPE_BYTES;
    }

    /**
     * Replaces the auto-configured route (which backs off on this bean name) to give {@code /mcp}, and
     * nothing else, a body limit that fits a file tool's base64 argument: the application-wide codec
     * limit stays at its default. A declared length above the limit is refused without reading the
     * body, and a chunked body stops being read at the limit.
     */
    @Bean
    @SuppressWarnings("unchecked")
    public RouterFunction<ServerResponse> webFluxStatelessServerRouterFunction(WebFluxStatelessServerTransport transport,
                                                                               McpProperties properties) {
        long limit = maxRequestBytes(properties.getMaxResourceSizeBytes());
        StringDecoder decoder = StringDecoder.allMimeTypes();
        decoder.setMaxInMemorySize((int) Math.min(limit, Integer.MAX_VALUE - 8));
        var readers = List.<org.springframework.http.codec.HttpMessageReader<?>>of(new DecoderHttpMessageReader<>(decoder));
        return ((RouterFunction<ServerResponse>) transport.getRouterFunction()).filter((request, next) -> {
            if (request.headers().contentLength().orElse(0L) > limit) {
                return tooLarge(limit);
            }
            return next.handle(ServerRequest.create(request.exchange(), readers))
                    .onErrorResume(McpConfig::isBodyLimit, _ -> tooLarge(limit));
        });
    }

    private static boolean isBodyLimit(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataBufferLimitException) {
                return true;
            }
        }
        return false;
    }

    private static Mono<ServerResponse> tooLarge(long limit) {
        return ServerResponse.status(HttpStatus.CONTENT_TOO_LARGE)
                .bodyValue("The request is above this server's %d-byte limit for /mcp.".formatted(limit));
    }
}
