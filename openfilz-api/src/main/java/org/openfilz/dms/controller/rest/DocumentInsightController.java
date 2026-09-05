package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.dto.request.InsightCategoryUpdate;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.ai.AiAccessPolicy;
import org.openfilz.dms.service.insight.AiDocumentInsightService;
import org.openfilz.dms.service.insight.InsightResult;
import org.openfilz.dms.service.insight.LearnedCategoryClassifier;
import org.openfilz.dms.utils.UserInfoService;
import org.openfilz.dms.service.insight.DocumentInsightStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the document insights (what OpenFilz derived from a document's content). There
 * is deliberately no write endpoint: insights are recomputed, never edited.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS)
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "Document Insights", description = "File metadata and AI-derived category / summary of a document")
public class DocumentInsightController implements UserInfoService {

    private final DocumentService documentService;
    // ObjectProvider, not @Lazy: DocumentInsightStore is a concrete class with no interface, so a
    // @Lazy injection point yields a CGLIB lazy-resolution proxy that has no reflection metadata
    // in a native image (MissingReflectionRegistrationError on CGLIB$FACTORY_DATA at boot).
    private final ObjectProvider<DocumentInsightStore> insightStoreProvider;
    private final ObjectProvider<AiAccessPolicy> accessPolicyProvider;
    private final ObjectProvider<IndexService> indexServiceProvider;
    private final AiProperties aiProperties;

    public DocumentInsightController(DocumentService documentService,
                                     ObjectProvider<DocumentInsightStore> insightStoreProvider,
                                     ObjectProvider<AiAccessPolicy> accessPolicyProvider,
                                     ObjectProvider<IndexService> indexServiceProvider,
                                     AiProperties aiProperties) {
        this.documentService = documentService;
        this.insightStoreProvider = insightStoreProvider;
        this.accessPolicyProvider = accessPolicyProvider;
        this.indexServiceProvider = indexServiceProvider;
        this.aiProperties = aiProperties;
    }

    @PatchMapping(value = "/{documentId}/insights", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Correct the kind of a document",
            description = "Sets the tier-2 category to one of the deployment's categories (openfilz.ai.insights.categories, "
                    + "or 'other'), recorded as written by the user: it is never overwritten by a non-forced backfill, it "
                    + "teaches the learned classifier, and the by-kind reorganisation and smart filing use it like a "
                    + "model's label. 400 for a kind the deployment does not know; 403 without modify access.")
    public Mono<DocumentInsightView> setCategory(@PathVariable UUID documentId, @RequestBody InsightCategoryUpdate body) {
        List<String> categories = aiProperties.getInsights().getCategories();
        String raw = body == null ? null : body.category();
        String category = InsightResult.category(raw, categories);
        if (raw == null || raw.isBlank() || (!InsightResult.OTHER.equals(category)
                && categories != null && categories.stream().noneMatch(c -> c.trim().equalsIgnoreCase(category)))
                || (InsightResult.OTHER.equals(category) && !InsightResult.OTHER.equalsIgnoreCase(raw.trim()))) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown kind '" + raw + "'; one of: " + String.join(", ", categories == null ? List.of(InsightResult.OTHER) : categories)));
        }
        DocumentInsightStore store = insightStoreProvider.getObject();
        return documentService.findDocumentToDownloadById(documentId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found")))
                .flatMap(document -> getConnectedUserEmail()
                        .flatMap(email -> {
                            AiAccessPolicy policy = accessPolicyProvider.getIfAvailable();
                            return policy == null || policy.permitAll() ? Mono.just(true) : policy.canModify(document.getId(), email);
                        })
                        .filter(Boolean::booleanValue)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot modify this document")))
                        .then(store.saveCategory(document.getId(), category, LearnedCategoryClassifier.USER_SOURCE,
                                AiDocumentInsightService.PROMPT_VERSION))
                        .then(mirror(document.getId(), category))
                        .then(store.find(document.getId()))
                        .map(DocumentInsightStore::toView));
    }

    private Mono<Void> mirror(UUID documentId, String category) {
        IndexService indexService = indexServiceProvider.getIfAvailable();
        if (indexService == null) {
            return Mono.empty();
        }
        return indexService.updateIndexFields(documentId, Map.of(OpenSearchDocumentKey.category.toString(), category))
                .onErrorResume(e -> Mono.empty());
    }

    @GetMapping(value = "/{documentId}/insights", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get the insights of a document",
            description = "Tier 1: the file's own metadata (title, author, dates, page count, language) captured at "
                    + "upload. Tier 2: the AI-derived category, summary, keywords and entities when "
                    + "openfilz.ai.insights.active is on. 404 when the document is not visible or has no insights yet.")
    public Mono<DocumentInsightView> getInsights(@PathVariable UUID documentId) {
        return documentService.findDocumentToDownloadById(documentId)
                .flatMap(document -> insightStoreProvider.getObject().find(document.getId()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No insights for this document")))
                .map(DocumentInsightStore::toView);
    }
}
