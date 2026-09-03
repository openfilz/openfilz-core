package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.repository.AiChatConversationRepository;
import org.openfilz.dms.repository.AiChatMessageRepository;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ai.AiAccessPolicy;
import org.openfilz.dms.service.ai.AiDocumentQueryService;
import org.openfilz.dms.service.ai.AiFallbackChain;
import org.openfilz.dms.service.ai.ChatClientAssembler;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.openfilz.dms.service.ai.DocumentAiToolsFactory;
import org.openfilz.dms.service.ai.PermitAllAiAccessPolicy;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the RAG access filter in {@link AiChatServiceImpl}: the vector store is shared
 * across all users, so under a per-document {@link AiAccessPolicy} the retrieved context
 * must only ever contain chunks of documents the requesting user can read — and chunks
 * that cannot be attributed to a document (no {@code document_id} metadata) are dropped
 * (fail closed). With the core permit-all policy, behaviour is unchanged.
 */
class AiRagAccessFilterTest {

    private static final String USER_EMAIL = "user-a@test.com";
    private static final UUID DOC_A_ID = UUID.randomUUID();
    private static final UUID DOC_B_ID = UUID.randomUUID();

    private static final String TEXT_A = "Readable document content about the quarterly financial report of user A. "
            + "This text is long enough to be included as a RAG chunk.";
    private static final String TEXT_B = "SECRET-CONTENT-OF-USER-B: salary spreadsheet details that must never leak. "
            + "This text is long enough to be included as a RAG chunk.";
    private static final String TEXT_NO_ID = "Chunk with no document id metadata, also long enough to be included.";

    private final VectorStore vectorStore = mock(VectorStore.class);

    private AiChatServiceImpl service(AiAccessPolicy policy) {
        return new AiChatServiceImpl(
                mock(UserChatClientResolver.class),
                mock(AiFallbackChain.class),
                mock(ChatClientAssembler.class),
                mock(DocumentAiToolsFactory.class),
                vectorStore,
                new AiProperties(),
                mock(AiChatConversationRepository.class),
                mock(AiChatMessageRepository.class),
                policy);
    }

    private DocumentAiTools tools() {
        return new DocumentAiTools(
                mock(DocumentService.class), mock(DocumentRepository.class), mock(StorageService.class),
                mock(AiDocumentQueryService.class), mock(ChatModel.class), new PermitAllAiAccessPolicy(),
                // no Authentication is bound in this unit test, so the role policy is a no-op here;
                // the capability gate is covered by DefaultAiToolRolePolicyTest and McpRoleEnforcementIT.
                (authentication, capability) -> true,
                mock(org.openfilz.dms.service.DocumentVersionService.class),
                new org.openfilz.dms.config.CommonProperties(),
                // real service, feature off by default — mint() returns null, links stay plain
                new org.openfilz.dms.security.DownloadTokenService(
                        new org.openfilz.dms.config.DownloadTokenProperties()));
    }

    private static Document chunk(UUID documentId, String name, String text, double score) {
        Map<String, Object> metadata = documentId == null
                ? Map.of("document_name", name)
                : Map.of("document_id", documentId.toString(), "document_name", name, "parent_id", "");
        return Document.builder().text(text).metadata(metadata).score(score).build();
    }

    private void givenVectorStoreReturnsAllThreeChunks() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                chunk(DOC_A_ID, "doc-a.txt", TEXT_A, 0.9),
                chunk(DOC_B_ID, "doc-b.txt", TEXT_B, 0.89),
                chunk(null, "no-id.txt", TEXT_NO_ID, 0.88)));
    }

    private String retrieveContext(AiChatServiceImpl service, DocumentAiTools tools, Set<String> ragNames) {
        Mono<String> result = ReflectionTestUtils.invokeMethod(
                service, "retrieveContext", "any question", tools, ragNames, USER_EMAIL);
        return result.block();
    }

    @Test
    void perDocumentPolicy_filtersUnreadableAndUnattributableChunks() {
        AiAccessPolicy policy = mock(AiAccessPolicy.class);
        when(policy.permitAll()).thenReturn(false);
        when(policy.canRead(eq(DOC_A_ID), eq(USER_EMAIL))).thenReturn(Mono.just(true));
        when(policy.canRead(eq(DOC_B_ID), eq(USER_EMAIL))).thenReturn(Mono.just(false));
        givenVectorStoreReturnsAllThreeChunks();

        DocumentAiTools tools = tools();
        Set<String> ragNames = new HashSet<>();
        String context = retrieveContext(service(policy), tools, ragNames);

        Assertions.assertTrue(context.contains(TEXT_A), "readable chunk must be in the context");
        Assertions.assertFalse(context.contains(TEXT_B), "unreadable chunk must NEVER reach the context");
        Assertions.assertFalse(context.contains("SECRET-CONTENT-OF-USER-B"), "no fragment of B's content may leak");
        Assertions.assertFalse(context.contains(TEXT_NO_ID), "unattributable chunks are dropped (fail closed)");
        Assertions.assertTrue(ragNames.contains("doc-a.txt"));
        Assertions.assertFalse(ragNames.contains("doc-b.txt"), "B's document name must not be registered for A");
        Assertions.assertFalse(tools.getRegistry().containsKey("doc-b.txt"),
                "B's document must not enter the doc-link registry");
    }

    @Test
    void permitAllPolicy_keepsCoreBehaviourUnchanged() {
        givenVectorStoreReturnsAllThreeChunks();

        String context = retrieveContext(service(new PermitAllAiAccessPolicy()), tools(), new HashSet<>());

        Assertions.assertTrue(context.contains(TEXT_A));
        Assertions.assertTrue(context.contains(TEXT_B), "permit-all keeps every relevant chunk (core model)");
        Assertions.assertTrue(context.contains(TEXT_NO_ID), "permit-all keeps chunks without document_id");
    }
}
