package org.openfilz.dms.controller.graphql;

import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.dto.request.FavoriteRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentQueryService;
import org.openfilz.dms.repository.graphql.AllFoldersDocumentQueryService;
import org.openfilz.dms.service.ThumbnailStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;
import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_THUMBNAILS;

@Controller
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DocumentQueryController {

    protected final DocumentQueryService documentService;
    private final AllFoldersDocumentQueryService allDocumentQueryService;

    private final CommonProperties commonProperties;

    @Autowired(required = false)
    private ThumbnailProperties thumbnailProperties;

    @Autowired(required = false)
    private ThumbnailStorageService thumbnailStorageService;

    public DocumentQueryController(@Qualifier("defaultDocumentQueryService") DocumentQueryService documentService, AllFoldersDocumentQueryService allDocumentQueryService, CommonProperties commonProperties) {
        this.documentService = documentService;
        this.allDocumentQueryService = allDocumentQueryService;
        this.commonProperties = commonProperties;
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

    /**
     * Resolves the thumbnailUrl field for FolderElementInfo type.
     * Returns the thumbnail URL if:
     * - Thumbnail feature is active
     * - Document is a FILE (not FOLDER)
     * - Content type is supported for thumbnail generation
     * - Thumbnail exists in storage
     */
    @SchemaMapping(typeName = "FolderElementInfo", field = "thumbnailUrl")
    public Mono<String> folderElementThumbnailUrl(FullDocumentInfo info) {
        return resolveThumbnailUrl(info);
    }

    /**
     * Resolves the thumbnailUrl field for DocumentInfo type.
     */
    @SchemaMapping(typeName = "DocumentInfo", field = "thumbnailUrl")
    public Mono<String> documentInfoThumbnailUrl(FullDocumentInfo info) {
        return resolveThumbnailUrl(info);
    }

    private Mono<String> resolveThumbnailUrl(FullDocumentInfo info) {
        // Feature not active
        if (thumbnailProperties == null || thumbnailStorageService == null) {
            return Mono.empty();
        }

        // Only files can have thumbnails
        if (info.type() == DocumentType.FOLDER) {
            return Mono.empty();
        }

        // Check if content type is supported
        if (!thumbnailProperties.isContentTypeSupported(info.contentType())) {
            return Mono.empty();
        }

        // Check if thumbnail exists and return URL
        return thumbnailStorageService.thumbnailExists(info.id())
                .flatMap(exists -> {
                    if (exists) {
                        String url = commonProperties.getApiPublicBaseUrl() + API_PREFIX + ENDPOINT_THUMBNAILS + "/img/" + info.id();
                        return Mono.just(url);
                    }
                    return Mono.empty();
                });
    }

}