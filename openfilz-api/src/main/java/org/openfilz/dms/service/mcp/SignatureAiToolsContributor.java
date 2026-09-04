package org.openfilz.dms.service.mcp;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.PdfToolsService;
import org.openfilz.dms.service.SignatureService;
import org.openfilz.dms.service.SignatureTemplateService;
import org.openfilz.dms.service.ai.AiAccessPolicy;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.SignatureAiTools;
import org.openfilz.dms.service.ai.SignatureAiToolsRuntimeHints;
import org.openfilz.dms.service.ai.ToolCapability;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registers the e-Sign tools ({@link SignatureAiTools}) with the shared tool layer, for external
 * MCP agents and the in-app assistant alike ({@link #exposeInChat()}).
 * <p>
 * Always advertised; when {@code openfilz.signature.active=false} each call answers with a
 * "disabled" message instead — the tool list is built once per deployment, like the role gate.
 * {@code sendForSignature} carries {@link ToolCapability#SIGNATURE_WRITE}, so the SIGN_REQUESTER
 * requirement of {@code openfilz.signature.require-requester-role} applies exactly as over REST.
 */
@Component
@Lazy
@RequiredArgsConstructor
@ImportRuntimeHints(SignatureAiToolsRuntimeHints.class)
public class SignatureAiToolsContributor implements McpToolContributor {

    public static final Map<String, ToolCapability> CAPABILITIES = Map.of(
            "listSignatureTemplates", ToolCapability.SIGNATURE_READ,
            "listSignatureEnvelopes", ToolCapability.SIGNATURE_READ,
            "getSignatureStatus", ToolCapability.SIGNATURE_READ,
            "sendForSignature", ToolCapability.SIGNATURE_WRITE);

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

    private final SignatureService signatureService;
    private final SignatureTemplateService templateService;
    private final DocumentRepository documentRepository;
    private final PdfToolsService pdfToolsService;
    private final AiAccessPolicy accessPolicy;
    private final AiToolRolePolicy rolePolicy;
    private final SignatureProperties signatureProperties;

    @Override
    public Object bind(String userEmail, Authentication authentication) {
        return new SignatureAiTools(signatureService, templateService, documentRepository, pdfToolsService,
                accessPolicy, rolePolicy, signatureProperties).forUser(userEmail, authentication);
    }

    @Override
    public Map<String, ToolCapability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * The tools enforce the role gate themselves, so the chat may call them directly — but only
     * while e-Sign is on: four tools that can only answer "disabled" would just dilute the model's
     * tool choice (and their schema costs tokens on every request). Read at request time, so the
     * runtime toggle keeps working in native images; the MCP list stays per-deployment as usual.
     */
    @Override
    public boolean exposeInChat() {
        return signatureProperties.isActive();
    }
}
