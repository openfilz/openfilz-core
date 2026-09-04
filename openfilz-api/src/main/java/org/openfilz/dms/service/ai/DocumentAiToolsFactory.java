package org.openfilz.dms.service.ai;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.StorageService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Creates a per-request {@link DocumentAiTools} instance for the chat pipeline.
 * <p>
 * Two reasons not to use the singleton bean there:
 * <ul>
 *   <li>The document registry used for link enrichment is per-conversation-turn state —
 *       on the singleton it was shared across concurrent users, so simultaneous chats
 *       could cross-contaminate each other's source links.</li>
 *   <li>The vision tool ({@code describeImage}) calls the {@link ChatModel} it was built
 *       with — per-request creation routes it to the user's own model (BYOK) instead of
 *       always the server default.</li>
 * </ul>
 * The singleton {@code DocumentAiTools} bean remains for direct tool usage and tests.
 */
@Component
@RequiredArgsConstructor
@Lazy
public class DocumentAiToolsFactory {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final AiDocumentQueryService queryService;
    private final AiAccessPolicy accessPolicy;
    private final AiToolRolePolicy rolePolicy;
    private final org.openfilz.dms.service.DocumentVersionService versionService;
    private final org.openfilz.dms.config.CommonProperties commonProperties;
    private final org.openfilz.dms.security.DownloadTokenService downloadTokenService;
    private final org.openfilz.dms.service.AuditService auditService;
    /**
     * The full-text index, present only when full-text search is configured: readDocumentContent
     * serves the text extracted at upload from it instead of downloading and parsing the file again.
     */
    private final org.springframework.beans.factory.ObjectProvider<org.openfilz.dms.service.IndexService> indexServiceProvider;
    private final org.springframework.beans.factory.ObjectProvider<org.openfilz.dms.service.insight.DocumentInsightStore> insightStoreProvider;

    /**
     * Create a tools instance bound to the requesting user: every document access inside
     * the tools is checked against the {@link AiAccessPolicy} for this user, and blocking
     * tool calls re-establish this Authentication in the Reactor context so security-aware
     * DAO overrides in extension layers see the caller's identity.
     */
    public DocumentAiTools create(ChatModel chatModel, String userEmail, org.springframework.security.core.Authentication authentication) {
        return new DocumentAiTools(documentService, documentRepository, storageService, queryService, chatModel, accessPolicy, rolePolicy, versionService, commonProperties, downloadTokenService,
                auditService, indexServiceProvider.getIfAvailable(), insightStoreProvider.getIfAvailable())
                .forUser(userEmail, authentication);
    }
}
