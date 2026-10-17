package org.openfilz.dms.controller.graphql;

import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchInfo;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.UserFavoriteRepository;
import org.openfilz.dms.service.DocumentSearchService;
import org.openfilz.dms.service.ThumbnailUrlResolver;
import org.openfilz.dms.utils.ContentTypeMapper;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


@Controller
@RequiredArgsConstructor
public class DocumentSearchGraphQlController implements UserInfoService {

    private final DocumentSearchService documentSearchService;
    private final ThumbnailUrlResolver thumbnailUrlResolver;
    private final UserFavoriteRepository userFavoriteRepository;

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
        // updatedAt is a cache-busting token so a replaced/restored document's thumbnail is re-fetched
        return thumbnailUrlResolver.resolveThumbnailUrl(info.id(), type, contentType, info.updatedAt());
    }

    /**
     * Resolves the favorite field for a page of DocumentSearchInfo hits: one query for the whole page,
     * so the results page can offer the favorite toggle on full-text hits too.
     */
    @BatchMapping(typeName = "DocumentSearchInfo", field = "favorite")
    public Mono<Map<DocumentSearchInfo, Boolean>> documentSearchInfoFavorite(List<DocumentSearchInfo> infos) {
        Set<UUID> ids = new HashSet<>();
        infos.forEach(info -> ids.add(info.id()));
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        return getConnectedUserEmail()
                .flatMap(email -> userFavoriteRepository.findFavoriteDocIds(email, ids).collect(HashSet<UUID>::new, Set::add))
                .map(favorites -> {
                    Map<DocumentSearchInfo, Boolean> result = new HashMap<>();
                    infos.forEach(info -> result.put(info, favorites.contains(info.id())));
                    return result;
                });
    }
}
