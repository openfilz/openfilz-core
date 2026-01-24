package org.openfilz.dms.controller.graphql;

import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchInfo;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.DocumentSearchService;
import org.openfilz.dms.service.ThumbnailUrlResolver;
import org.openfilz.dms.utils.ContentTypeMapper;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.util.List;


@Controller
@RequiredArgsConstructor
public class DocumentSearchGraphQlController {

    private final DocumentSearchService documentSearchService;
    private final ThumbnailUrlResolver thumbnailUrlResolver;

    @QueryMapping
    public Mono<DocumentSearchResult> searchDocuments(
            @Argument String query,
            @Argument List<FilterInput> filters,
            @Argument SortInput sort,
            @Argument int page,
            @Argument int size,
            DataFetchingEnvironment environment) {

        return documentSearchService.search(query, filters, sort, page, size, environment);
    }

    /**
     * Resolves the thumbnailUrl field for DocumentSearchInfo type.
     */
    @SchemaMapping(typeName = "DocumentSearchInfo", field = "thumbnailUrl")
    public Mono<String> documentSearchInfoThumbnailUrl(DocumentSearchInfo info) {
        DocumentType type;
        String contentType;
        // Determine document type from extension (null extension = folder)
        if(info.extension() == null) {
            type = DocumentType.FOLDER;
            contentType = null;
        } else {
            type = DocumentType.FILE;
            contentType = info.contentType() == null ? ContentTypeMapper.getContentType(info.extension()) :  info.contentType();
        }
        return thumbnailUrlResolver.resolveThumbnailUrl(info.id(), type, contentType);
    }
}