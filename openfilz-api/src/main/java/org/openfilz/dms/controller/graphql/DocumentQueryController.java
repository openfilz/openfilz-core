package org.openfilz.dms.controller.graphql;

import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.dto.request.FavoriteRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.repository.DocumentQueryService;
import org.openfilz.dms.repository.graphql.AllFoldersDocumentQueryService;
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
    private final AllFoldersDocumentQueryService allDocumentQueryService;

    public DocumentQueryController(@Qualifier("defaultDocumentQueryService") DocumentQueryService documentService, AllFoldersDocumentQueryService allDocumentQueryService) {
        this.documentService = documentService;
        this.allDocumentQueryService = allDocumentQueryService;
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

    @QueryMapping
    public Flux<FullDocumentInfo> listFavorites(@Argument @NotNull FavoriteRequest request,
                                             DataFetchingEnvironment environment) {
        return allDocumentQueryService.findAll(request.toListFolderRequest(), environment);
    }

    @QueryMapping
    public Mono<Long> countFavorites(@Argument FavoriteRequest request,
                            DataFetchingEnvironment environment) {
        return allDocumentQueryService.count(request.toListFolderRequest(), environment);
    }

}