package org.openfilz.dms.controller.graphql;

import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.repository.DocumentQueryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Controller
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DocumentQueryController {

    protected final DocumentQueryService documentService;

    public DocumentQueryController(@Qualifier("defaultDocumentQueryService") DocumentQueryService documentService) {
        this.documentService = documentService;
    }

    @QueryMapping
    public Mono<FullDocumentInfo> documentById(@Argument @NotNull UUID id,
                                               DataFetchingEnvironment environment) {
        return documentService.findById(id, environment);
    }

    @QueryMapping
    public Flux<FullDocumentInfo> listFolder(@Argument @NotNull ListFolderRequest request,
                                             DataFetchingEnvironment environment) {
        return documentService.findAll(request, environment);
    }

    @QueryMapping
    public Mono<Long> count(@Argument ListFolderRequest request,
                            DataFetchingEnvironment environment) {
        return documentService.count(request, environment);
    }

}