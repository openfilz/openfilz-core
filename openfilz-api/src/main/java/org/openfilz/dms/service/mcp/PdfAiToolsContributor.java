package org.openfilz.dms.service.mcp;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.PdfToolsProperties;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.PdfToolsService;
import org.openfilz.dms.service.ai.AiAccessPolicy;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.PdfAiTools;
import org.openfilz.dms.service.ai.PdfAiToolsRuntimeHints;
import org.openfilz.dms.service.ai.ToolCapability;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registers the PDF tools ({@link PdfAiTools}) with the shared tool layer, so merge / split /
 * rotate / organise are available to external MCP agents <em>and</em> to the in-app assistant
 * ({@link #exposeInChat()}), under the same authentication, role and read-only enforcement as the
 * document tools.
 * <p>
 * The tools are always advertised; when {@code openfilz.pdf-tools.active=false} each call answers
 * with a "disabled" message instead (the tool list is built once per deployment, exactly like the
 * role gate — see {@link McpToolCallbackProvider}).
 */
@Component
@Lazy
@RequiredArgsConstructor
@ImportRuntimeHints(PdfAiToolsRuntimeHints.class)
public class PdfAiToolsContributor implements McpToolContributor {

    public static final Map<String, ToolCapability> CAPABILITIES = Map.of(
            "getPdfInfo", ToolCapability.DOCUMENT_READ,
            "mergePdfs", ToolCapability.DOCUMENT_WRITE,
            "splitPdf", ToolCapability.DOCUMENT_WRITE,
            "rotatePdf", ToolCapability.DOCUMENT_WRITE,
            "deletePdfPages", ToolCapability.DOCUMENT_WRITE,
            "extractPdfPages", ToolCapability.DOCUMENT_WRITE,
            "reorderPdfPages", ToolCapability.DOCUMENT_WRITE);

    /** Advertised in every MCP mode. */
    public static final Set<String> READ_ONLY_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> !e.getValue().isMutating())
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

    /** Withheld in {@code READ_ONLY} mode. */
    public static final Set<String> MUTATING_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> e.getValue().isMutating())
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

    private final PdfToolsService pdfToolsService;
    private final DocumentRepository documentRepository;
    private final AiAccessPolicy accessPolicy;
    private final AiToolRolePolicy rolePolicy;
    private final PdfToolsProperties props;

    @Override
    public Object bind(String userEmail, Authentication authentication) {
        return new PdfAiTools(pdfToolsService, documentRepository, accessPolicy, rolePolicy, props)
                .forUser(userEmail, authentication);
    }

    @Override
    public Map<String, ToolCapability> capabilities() {
        return CAPABILITIES;
    }

    /** The PDF tools enforce the role gate themselves (like {@code DocumentAiTools}), so the chat may call them directly. */
    @Override
    public boolean exposeInChat() {
        return true;
    }
}
