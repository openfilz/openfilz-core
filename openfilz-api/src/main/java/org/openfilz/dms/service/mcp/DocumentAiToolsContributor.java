package org.openfilz.dms.service.mcp;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.openfilz.dms.service.ai.DocumentAiToolsFactory;
import org.openfilz.dms.service.ai.ToolCapability;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;

import static java.util.Map.entry;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The core {@link McpToolContributor}: exposes {@link DocumentAiTools} — the same tools the in-app
 * AI assistant uses — over MCP. Community deployments serve exactly these; the enterprise edition
 * adds more through its own contributor.
 *
 * @see McpToolCallbackProvider
 */
@Component
@Lazy
@RequiredArgsConstructor
public class DocumentAiToolsContributor implements McpToolContributor {

    /**
     * The document tools and what each needs permission to do. Kept here, next to the tools it
     * classifies, rather than in the provider — the provider merges the maps of all contributors.
     */
    public static final Map<String, ToolCapability> CAPABILITIES = Map.ofEntries(
            entry("whoami", ToolCapability.IDENTITY_READ),
            entry("queryDocuments", ToolCapability.DOCUMENT_READ),
            entry("readDocumentContent", ToolCapability.DOCUMENT_READ),
            entry("getDocumentPath", ToolCapability.DOCUMENT_READ),
            entry("describeImage", ToolCapability.DOCUMENT_READ),
            entry("writeFile", ToolCapability.DOCUMENT_WRITE),
            entry("createBlankDocument", ToolCapability.DOCUMENT_WRITE),
            entry("createFolder", ToolCapability.DOCUMENT_WRITE),
            entry("moveDocuments", ToolCapability.DOCUMENT_WRITE),
            entry("renameDocument", ToolCapability.DOCUMENT_WRITE),
            entry("getMetadata", ToolCapability.DOCUMENT_READ),
            entry("searchByMetadata", ToolCapability.DOCUMENT_READ),
            entry("updateMetadata", ToolCapability.DOCUMENT_WRITE),
            entry("deleteMetadata", ToolCapability.DOCUMENT_WRITE),
            entry("deleteDocument", ToolCapability.DOCUMENT_DELETE),
            entry("listVersions", ToolCapability.DOCUMENT_READ),
            entry("restoreVersion", ToolCapability.DOCUMENT_WRITE),
            entry("downloadDocument", ToolCapability.DOCUMENT_READ),
            entry("getDocumentActivity", ToolCapability.AUDIT_READ));

    /** The read/search tools — advertised in every mode. Derived so it cannot drift from the map. */
    public static final Set<String> READ_ONLY_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> !e.getValue().isMutating())
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

    /** The mutating tools — withheld in {@code READ_ONLY} mode. */
    public static final Set<String> MUTATING_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> e.getValue().isMutating())
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

    private final DocumentAiToolsFactory toolsFactory;

    /**
     * Optional: a deployment can serve MCP tools with no OpenFilz-side chat model at all — the
     * calling agent brings its own. Only {@code describeImage} consumes it, and it degrades
     * explicitly when absent.
     */
    private final ObjectProvider<ChatModel> chatModelProvider;

    @Override
    public Object bind(String userEmail, Authentication authentication) {
        // The definitions template (both args null) must NOT resolve a ChatModel: that happens
        // while the context is still refreshing and closes a bean cycle
        // (toolCallbackResolver -> getToolCallbacks -> ollamaChatModel -> toolCallingManager
        // -> toolCallbackResolver). A ChatModel cannot change a tool's definition anyway; only
        // describeImage's execution needs one, and that is the bound path below.
        ChatModel chatModel = (authentication == null && userEmail == null)
                ? null
                : chatModelProvider.getIfAvailable();
        return toolsFactory.create(chatModel, userEmail, authentication);
    }

    @Override
    public Map<String, ToolCapability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * {@code downloadDocument} stays in {@link #CAPABILITIES} (it is part of the advertised
     * surface, and the read-only/role classification is still the source of truth) but is served
     * over MCP by {@link McpDocumentResources}: its result carries a {@code resource_link}
     * content block next to the text fallback, which the adapted String route cannot express.
     */
    @Override
    public Set<String> nativeTools() {
        return Set.of(McpDocumentResources.DOWNLOAD_TOOL);
    }
}
