package org.openfilz.dms.service.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.McpProperties;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.ReorganizationInventoryCache;
import org.openfilz.dms.service.ai.ToolCapability;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.openfilz.dms.security.JwtTokenParser.EMAIL;

/**
 * Exposes the tools contributed by every {@link McpToolContributor} — the same tools the in-app AI
 * assistant calls, plus any the enterprise edition adds — as MCP tools, bound to the calling user.
 * <p>
 * <b>The MCP layer never defines a tool.</b> It adapts the {@link ToolCallback}s harvested from the
 * contributors and nothing else, so a capability added to a tool object is gained by both the chat
 * assistant and every external agent at once. New tools arrive by registering a contributor, not by
 * changing this class.
 * <p>
 * Three things happen per call that a plain {@code MethodToolCallbackProvider} would not do, and
 * they apply uniformly to every contributor's tools:
 * <ol>
 *   <li><b>Per-user binding.</b> A fresh tools instance is built per call through the owning
 *       contributor, carrying the caller's email and {@link Authentication} so the access policy
 *       (permit-all in core, ownership/share-backed in enterprise) and secure DAO overrides see the
 *       right user. A shared instance would leak one agent's documents into another's results.</li>
 *   <li><b>Role enforcement.</b> The caller's OpenFilz roles must permit the tool's
 *       {@link ToolCapability}, exactly as over REST ({@link AiToolRolePolicy}).</li>
 *   <li><b>Read-only enforcement.</b> Mutating tools are withheld from {@code tools/list} unless
 *       {@code openfilz.mcp.mode=read-write}.</li>
 * </ol>
 * The caller's identity arrives via the {@link McpTransportContext} placed in the
 * {@link ToolContext} by the MCP server; {@link McpAuthenticationWebFilter} is what puts it there.
 * It is never read from the tool arguments — an agent cannot ask to act as someone else.
 * <p>
 * Note the role gate cannot filter {@code tools/list}: Spring AI converts the callbacks into MCP
 * tool specifications once, at startup, so the advertised list is per-deployment, not per-caller. A
 * READER therefore still sees a write tool advertised and is refused on call — the same shape as
 * {@code READ_ONLY} mode.
 */
@Slf4j
@Component
@Lazy
@RequiredArgsConstructor
public class McpToolCallbackProvider implements ToolCallbackProvider, UserInfoService {

    private final List<McpToolContributor> contributors;
    private final McpProperties mcpProperties;
    private final AiToolRolePolicy rolePolicy;
    /** Dropped for the caller after every mutating call, so their next inventory sees the change. */
    private final ReorganizationInventoryCache inventoryCache;

    @Override
    public ToolCallback[] getToolCallbacks() {
        if (!mcpProperties.isActive()) {
            return new ToolCallback[0];
        }
        Map<String, ToolCapability> capabilities = aggregateCapabilities();
        boolean readOnly = mcpProperties.isReadOnly();

        List<ToolCallback> callbacks = new ArrayList<>();
        for (McpToolContributor contributor : contributors) {
            for (ToolCallback template : templateCallbacks(contributor)) {
                String name = template.getToolDefinition().name();
                if (contributor.nativeTools().contains(name)) {
                    // Served by a hand-built MCP specification (multi-block content the String
                    // pipeline cannot express — see McpDocumentResources). Skipping keeps it out
                    // of the adapted route so the name is not advertised twice.
                    continue;
                }
                ToolCapability capability = capabilities.get(name);
                if (capability == null) {
                    // Fail closed: a tool whose contributor did not classify it is not advertised
                    // and, if reached, refused. Guessing its capability is how a write gets a
                    // read's role.
                    log.error("MCP tool '{}' has no ToolCapability — not advertised. Add it to the "
                            + "contributor's capabilities() map.", name);
                    continue;
                }
                if (readOnly && capability.isMutating()) {
                    continue;
                }
                callbacks.add(new UserBoundToolCallback(template.getToolDefinition(), contributor, capability));
            }
        }
        log.info("MCP server exposing {} tools (mode={}, {} contributor(s))",
                callbacks.size(), mcpProperties.getMode(), contributors.size());
        return callbacks.toArray(ToolCallback[]::new);
    }

    /**
     * The capability of every advertised tool, merged across contributors. A tool name claimed by
     * two contributors is a wiring bug — logged and resolved first-wins rather than silently
     * shadowing, so it surfaces without aborting startup.
     */
    private Map<String, ToolCapability> aggregateCapabilities() {
        Map<String, ToolCapability> merged = new HashMap<>();
        for (McpToolContributor contributor : contributors) {
            contributor.capabilities().forEach((name, capability) -> {
                ToolCapability existing = merged.putIfAbsent(name, capability);
                if (existing != null) {
                    log.error("MCP tool '{}' is declared by two contributors — keeping {}, ignoring {}",
                            name, existing, capability);
                }
            });
        }
        return merged;
    }

    /**
     * A mutating tool must run as a real, identifiable user, or its effects can be neither scoped
     * nor attributed: a token with no {@code email} claim — a bare service account — would
     * otherwise write as {@code ANONYMOUS_USER} in the tamper-evident audit chain and against a
     * null document scope. Refuse such writes so an agent must authenticate as a dedicated user
     * (its token carrying that user's email) or act on behalf of one via token exchange.
     * <p>
     * Reads are intentionally not blocked here — the access policy returns nothing for a null user
     * in the enterprise edition, and a read leaves the audit chain untouched.
     *
     * @return a refusal message, or {@code null} when the operation may proceed
     */
    static String mutationRequiresIdentity(ToolCapability capability, String userEmail) {
        if (capability.isMutating() && (userEmail == null || userEmail.isBlank())) {
            return "Not permitted: this operation changes data and requires an identified OpenFilz "
                    + "user. Your token has no user identity (email) — authenticate as a dedicated "
                    + "user, or act on behalf of one.";
        }
        return null;
    }

    /**
     * Tool definitions (name, description, JSON input schema) harvested from a contributor's
     * unbound template. Definitions are static — only execution needs a user — so this uses
     * {@code bind(null, null)}. See {@link McpToolContributor#bind} for why the template must not
     * touch per-request resources (notably: resolve no {@code ChatModel}, which would close a
     * startup bean cycle).
     */
    private ToolCallback[] templateCallbacks(McpToolContributor contributor) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(contributor.bind(null, null))
                .build()
                .getToolCallbacks();
    }

    /**
     * Resolve the caller's {@link Authentication} from the MCP transport context. Returns
     * {@code null} when absent, which the callback turns into a refusal rather than an anonymous
     * execution — failing closed.
     */
    private static Authentication authenticationFrom(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object exchange = toolContext.getContext().get(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY);
        return exchange instanceof McpTransportContext transportContext
                ? McpAuthenticationWebFilter.authenticationFrom(transportContext)
                : null;
    }

    /**
     * A {@link ToolCallback} that carries only the tool <em>definition</em> and the contributor +
     * capability it belongs to; the executing instance is built per call, bound to whoever is
     * calling.
     */
    private final class UserBoundToolCallback implements ToolCallback {

        private final ToolDefinition toolDefinition;
        private final McpToolContributor contributor;
        private final ToolCapability capability;

        private UserBoundToolCallback(ToolDefinition toolDefinition, McpToolContributor contributor,
                                      ToolCapability capability) {
            this.toolDefinition = toolDefinition;
            this.contributor = contributor;
            this.capability = capability;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        /**
         * No {@link ToolContext} means no caller identity. Refuse rather than run unbound — an
         * unbound tools instance would be checked against a null user (enterprise) or the
         * permit-all policy (core), exposing everything.
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
            if (mcpProperties.isReadOnly() && capability.isMutating()) {
                return "This OpenFilz MCP server is read-only: '%s' is not available."
                        .formatted(toolDefinition.name());
            }
            Authentication authentication = authenticationFrom(toolContext);
            if (authentication == null) {
                log.warn("MCP tool '{}' called without an authenticated caller — refused", toolDefinition.name());
                return "Not authenticated: this MCP server requires a bearer token identifying an OpenFilz user.";
            }
            if (!rolePolicy.isAllowed(authentication, capability)) {
                log.warn("MCP tool '{}' refused for {}: role does not grant {}",
                        toolDefinition.name(), authentication.getName(), capability);
                return "Not permitted: your OpenFilz role does not allow this operation (%s)."
                        .formatted(capability);
            }
            // Fresh, user-bound tools per call: any per-turn state inside the tool object is the
            // caller's alone, and the access policy has to see this caller and no other.
            String userEmail = getUserAttribute(authentication, EMAIL);
            // A mutating tool must run as a real, identifiable user, or its effects cannot be
            // scoped or attributed. A token with no email claim — a bare service account — would
            // otherwise write as ANONYMOUS_USER in the audit chain and against a null document
            // scope. Refuse: the agent must authenticate as a dedicated user (its token carrying
            // that user's email) or act on behalf of one (token exchange). Reads are left to the
            // access policy, which returns nothing for a null user in the enterprise edition.
            String identityRefusal = mutationRequiresIdentity(capability, userEmail);
            if (identityRefusal != null) {
                log.warn("MCP mutating tool '{}' refused: caller '{}' has no user identity (email claim).",
                        toolDefinition.name(), authentication.getName());
                return identityRefusal;
            }
            ToolCallback bound = Arrays.stream(MethodToolCallbackProvider.builder()
                            .toolObjects(contributor.bind(userEmail, authentication))
                            .build()
                            .getToolCallbacks())
                    .filter(callback -> callback.getToolDefinition().name().equals(toolDefinition.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "MCP tool '" + toolDefinition.name() + "' vanished from its contributor"));
            log.debug("[MCP] {} invoked by {}", toolDefinition.name(), authentication.getName());
            try {
                return bound.call(toolInput);
            } finally {
                if (capability.isMutating()) {
                    inventoryCache.invalidate(userEmail);
                }
            }
        }
    }
}
