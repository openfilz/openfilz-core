package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.insight.DocumentInsightStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Read side of the document insights (what OpenFilz derived from a document's content). There
 * is deliberately no write endpoint: insights are recomputed, never edited.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS)
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "Document Insights", description = "File metadata and AI-derived category / summary of a document")
public class DocumentInsightController {

    private final DocumentService documentService;
    // ObjectProvider, not @Lazy: DocumentInsightStore is a concrete class with no interface, so a
    // @Lazy injection point yields a CGLIB lazy-resolution proxy that has no reflection metadata
    // in a native image (MissingReflectionRegistrationError on CGLIB$FACTORY_DATA at boot).
    private final ObjectProvider<DocumentInsightStore> insightStoreProvider;

    public DocumentInsightController(DocumentService documentService,
                                     ObjectProvider<DocumentInsightStore> insightStoreProvider) {
        this.documentService = documentService;
        this.insightStoreProvider = insightStoreProvider;
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
