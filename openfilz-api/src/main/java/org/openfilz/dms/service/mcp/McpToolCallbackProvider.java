package org.openfilz.dms.service.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.McpProperties;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.openfilz.dms.service.ai.DocumentAiToolsFactory;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

import static org.openfilz.dms.security.JwtTokenParser.EMAIL;

/**
 * Exposes {@link DocumentAiTools} — the very tools the in-app AI assistant calls — as MCP
 * tools, bound to the calling user.
 * <p>
 * <b>The MCP layer never defines a tool.</b> It adapts the existing {@link ToolCallback}s and
 * nothing else, so any capability added to {@code DocumentAiTools} is gained by both the chat
 * assistant and every external agent at once.
 * <p>
 * Two things have to happen per call that a plain {@code MethodToolCallbackProvider} would not
 * do:
 * <ol>
 *   <li><b>Per-user binding.</b> A tools instance is created per call through
 *       {@link DocumentAiToolsFactory}, carrying the caller's email and {@link Authentication}
 *       so {@code AiAccessPolicy} (permit-all in core, ownership/share-backed in the enterprise
 *       layer) and the secure DAO overrides both see the right user. A shared instance would
 *       leak one agent's documents into another's results.</li>
 *   <li><b>Read-only enforcement.</b> Mutating tools are withheld from {@code tools/list}
 *       unless {@code openfilz.mcp.mode=read-write}.</li>
 * </ol>
 * The caller's identity arrives via the {@link McpTransportContext} placed in the
 * {@link ToolContext} by the MCP server; {@link McpAuthenticationWebFilter} is what puts it
 * there. It is never read from the tool arguments — an agent cannot ask to act as someone else.
 */
@Slf4j
@Component
@Lazy
@RequiredArgsConstructor
public class McpToolCallbackProvider implements ToolCallbackProvider, UserInfoService {

    /**
     * Tools that create or change content. Withheld in {@code READ_ONLY} mode.
     * Kept as an explicit allow-list rather than inferred: a new mutating tool added to
     * {@code DocumentAiTools} must be classified deliberately, and
     * {@code McpToolCallbackProviderTest} fails if an unknown tool name appears.
     */
    public static final Set<String> MUTATING_TOOLS = Set.of(
            "writeFile", "createFolder", "moveDocuments", "renameDocument");

    /** Every tool name this provider expects to find on {@link DocumentAiTools}. */
    public static final Set<String> READ_ONLY_TOOLS = Set.of(
            "queryDocuments", "readDocumentContent", "getDocumentPath", "describeImage");

    private final DocumentAiToolsFactory toolsFactory;
    private final McpProperties mcpProperties;

    /**
     * Optional: a deployment can serve MCP tools with no OpenFilz-side chat model configured at
     * all — the calling agent brings its own. Only {@code describeImage} consumes it, and it
     * degrades explicitly when absent.
     */
    private final ObjectProvider<ChatModel> chatModelProvider;

    @Override
    public ToolCallback[] getToolCallbacks() {
        if (!mcpProperties.isActive()) {
            return new ToolCallback[0];
        }
        boolean readOnly = mcpProperties.isReadOnly();
        ToolCallback[] callbacks = Arrays.stream(templateCallbacks())
                .filter(callback -> !readOnly || !MUTATING_TOOLS.contains(callback.getToolDefinition().name()))
                .map(callback -> (ToolCallback) new UserBoundToolCallback(callback.getToolDefinition()))
                .toArray(ToolCallback[]::new);
        log.info("MCP server exposing {} tools (mode={})", callbacks.length, mcpProperties.getMode());
        return callbacks;
    }

    /**
     * Tool definitions (name, description, JSON input schema) harvested from an unbound tools
     * instance. Definitions are static — only the execution needs a user.
     */
    private ToolCallback[] templateCallbacks() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(newTools(null))
                .build()
                .getToolCallbacks();
    }

    private DocumentAiTools newTools(Authentication authentication) {
        String userEmail = authentication == null ? null : getUserAttribute(authentication, EMAIL);
        return toolsFactory.create(chatModelProvider.getIfAvailable(), userEmail, authentication);
    }

    /**
     * Resolve the caller's {@link Authentication} from the MCP transport context.
     * Returns {@code null} when absent, which the callback turns into a refusal rather than an
     * anonymous execution — failing closed.
     */
    private static Authentication authenticationFrom(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object exchange = toolContext.getContext().get(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY);
        if (exchange instanceof McpTransportContext transportContext
                && transportContext.get(McpAuthenticationWebFilter.AUTHENTICATION_ATTRIBUTE)
                    instanceof Authentication authentication) {
            return authentication;
        }
        return null;
    }

    /**
     * A {@link ToolCallback} that carries only the tool <em>definition</em>; the executing
     * instance is built per call, bound to whoever is calling.
     */
    private final class UserBoundToolCallback implements ToolCallback {

        private final ToolDefinition toolDefinition;

        private UserBoundToolCallback(ToolDefinition toolDefinition) {
            this.toolDefinition = toolDefinition;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        /**
         * No {@link ToolContext} means no caller identity. Refuse rather than run unbound — in
         * the enterprise layer an unbound tools instance would be checked against a null user
         * and, in core, against the permit-all policy, which would expose everything.
         */
        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            if (!mcpProperties.isActive()) {
                return "The MCP server is disabled on this OpenFilz deployment.";
            }
            if (mcpProperties.isReadOnly() && MUTATING_TOOLS.contains(toolDefinition.name())) {
                return "This OpenFilz MCP server is read-only: '%s' is not available."
                        .formatted(toolDefinition.name());
            }
            Authentication authentication = authenticationFrom(toolContext);
            if (authentication == null) {
                log.warn("MCP tool '{}' called without an authenticated caller — refused", toolDefinition.name());
                return "Not authenticated: this MCP server requires a bearer token identifying an OpenFilz user.";
            }
            // Fresh, user-bound tools per call: the document registry inside DocumentAiTools is
            // per-turn state, and the access policy has to see this caller and no other.
            ToolCallback bound = Arrays.stream(MethodToolCallbackProvider.builder()
                            .toolObjects(newTools(authentication))
                            .build()
                            .getToolCallbacks())
                    .filter(callback -> callback.getToolDefinition().name().equals(toolDefinition.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "MCP tool '" + toolDefinition.name() + "' vanished from DocumentAiTools"));
            log.debug("[MCP] {} invoked by {}", toolDefinition.name(), authentication.getName());
            return bound.call(toolInput);
        }
    }
}
