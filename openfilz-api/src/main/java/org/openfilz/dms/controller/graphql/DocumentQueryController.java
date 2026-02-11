package org.openfilz.dms.controller.graphql;

import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.dto.request.FavoriteRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.repository.DocumentQueryService;
import org.openfilz.dms.repository.graphql.AllFoldersDocumentQueryService;
import org.openfilz.dms.service.ThumbnailUrlResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Controller
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DocumentQueryController {

    protected final DocumentQueryService documentService;
    private final AllFoldersDocumentQueryService allDocumentQueryService;
    private final ThumbnailUrlResolver thumbnailUrlResolver;

    public DocumentQueryController(@Qualifier("defaultDocumentQueryService") DocumentQueryService documentService,
                                   AllFoldersDocumentQueryService allDocumentQueryService,
                                   ThumbnailUrlResolver thumbnailUrlResolver) {
        this.documentService = documentService;
        this.allDocumentQueryService = allDocumentQueryService;
        this.thumbnailUrlResolver = thumbnailUrlResolver;
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
    public Flux<FullDocumentInfo> listAllFolder(@Argument @NotNull ListFolderRequest request,
                                             DataFetchingEnvironment environment) {
        return allDocumentQueryService.findAll(request, environment);
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

    /**
     * Resolves the thumbnailUrl field for FolderElementInfo type.
     */
    @SchemaMapping(typeName = "FolderElementInfo", field = "thumbnailUrl")
    public Mono<String> folderElementThumbnailUrl(FullDocumentInfo info) {
        return thumbnailUrlResolver.resolveThumbnailUrl(info.id(), info.type(), info.contentType());
    }

    /**
     * Resolves the thumbnailUrl field for DocumentInfo type.
     */
    @SchemaMapping(typeName = "DocumentInfo", field = "thumbnailUrl")
    public Mono<String> documentInfoThumbnailUrl(FullDocumentInfo info) {
        return thumbnailUrlResolver.resolveThumbnailUrl(info.id(), info.type(), info.contentType());
    }

}