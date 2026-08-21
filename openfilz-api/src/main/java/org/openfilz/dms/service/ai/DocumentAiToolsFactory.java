package org.openfilz.dms.service.ai;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.StorageService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class DocumentAiToolsFactory {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final AiDocumentQueryService queryService;

    public DocumentAiTools create(ChatModel chatModel) {
        return new DocumentAiTools(documentService, documentRepository, storageService, queryService, chatModel);
    }
}
