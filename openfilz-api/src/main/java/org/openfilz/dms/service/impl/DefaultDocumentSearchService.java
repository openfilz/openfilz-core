package org.openfilz.dms.service.impl;

import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.PageCriteria;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchInfo;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.repository.DocumentQueryService;
import org.openfilz.dms.service.DocumentSearchService;
import org.openfilz.dms.utils.ContentTypeMapper;
import org.openfilz.dms.utils.FileUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true)
public class DefaultDocumentSearchService implements DocumentSearchService {

    private final DocumentQueryService documentQueryService;


    @Override
    public Mono<DocumentSearchResult> search(String query, List<FilterInput> filters, SortInput sort, int page, int size, DataFetchingEnvironment environment) {
        ListFolderRequest searchListFolderRequest = toListFolderRequest(query, filters, sort, page, size);
        ListFolderRequest countListFolderRequest = new ListFolderRequest(
                searchListFolderRequest.id(),
                searchListFolderRequest.type(),
                searchListFolderRequest.contentType(),
                searchListFolderRequest.name(),
                searchListFolderRequest.nameLike(),
                searchListFolderRequest.metadata(),
                searchListFolderRequest.size(),
                searchListFolderRequest.createdAtAfter(),
                searchListFolderRequest.createdAtBefore(),
                searchListFolderRequest.updatedAtAfter(),
                searchListFolderRequest.updatedAtBefore(),
                searchListFolderRequest.createdBy(),
                searchListFolderRequest.updatedBy(),
                null
        );
        return Mono.zip(
                documentQueryService.count(countListFolderRequest, environment),
                documentQueryService.findAll(searchListFolderRequest, environment).map(this::toDocumentSearchInfo).collectList()
        ).map(tuple -> new DocumentSearchResult(tuple.getT1(), tuple.getT2()));
    }

   private DocumentSearchInfo toDocumentSearchInfo(FullDocumentInfo fullDoc) {
        return new DocumentSearchInfo(
                fullDoc.id(),
                fullDoc.name(),
                FileUtils.getFileExtension(fullDoc.type(), fullDoc.name()),
                fullDoc.size(),
                fullDoc.parentId(),
                fullDoc.createdAt() != null ? fullDoc.createdAt().toString() : null,
                fullDoc.updatedAt() != null ? fullDoc.updatedAt().toString() : null,
                fullDoc.createdBy(),
                fullDoc.updatedBy()
        );
    }

    private ListFolderRequest toListFolderRequest(String query,
                                                  List<FilterInput> filters,
                                                  SortInput sort,
                                                  int page,
                                                  int size) {
        if(filters == null || filters.isEmpty()) {
            return new ListFolderRequest(null,
                    null,
                    null,
                    null,
                    query != null ? query.toLowerCase() : null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    toPageCriteria(sort, page, size)
            );
        }
        Map<String, String> map = new HashMap<>(filters.size());
        filters.forEach(filterInput -> {
            map.put(filterInput.field(),  filterInput.value());
        });
        return new ListFolderRequest(toUuid(map.get(FILTER_PARENT_ID)),
                toDocumentType(map),
                ContentTypeMapper.getContentType(map.get(FILTER_EXTENSION)),
                null,
                query,
                totMetadataMap(map),
                toLong(map),
                toDate(map, FILTER_CREATED_AT_AFTER),
                toDate(map, FILTER_CREATED_AT_BEFORE),
                toDate(map, FILTER_UPDATED_AT_AFTER),
                toDate(map, FILTER_UPDATED_AT_BEFORE),
                map.get(FILTER_CREATED_BY),
                map.get(FILTER_UPDATED_BY),
                toPageCriteria(sort, page, size)
                );
    }

    private Long toLong(Map<String, String> map) {
        String size = map.get(FILTER_SIZE);
        if(size != null) {
            return Long.valueOf(size);
        }
        return null;
    }

    private PageCriteria toPageCriteria(SortInput sort, int page, int size) {
        String sortBy = null;
        SortOrder sortOrder = null;
        if(sort != null) {
            sortBy = sort.field();
            sortOrder = sort.order();
        }
        return new PageCriteria(sortBy,
                sortOrder,
                page,
                size);
    }

    private Map<String, Object> totMetadataMap(Map<String, String> map) {
        Map<String, Object> metadataMap = map.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(FILTER_METADATA))
                .collect(Collectors.toMap(entry -> entry.getKey().substring(FILTER_METADATA.length()), Map.Entry::getValue));
        return metadataMap.isEmpty() ? null : metadataMap;
    }

    private OffsetDateTime toDate(Map<String, String> map, String field) {
        String date = map.get(field);
        if(date == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(date);
        } catch (Exception e) {
            log.error("Error parsing date: {}", date, e);
            return null;
        }
    }

    private DocumentType toDocumentType(Map<String, String> map) {
        String type = map.get(FILTER_TYPE);
        return type != null ? DocumentType.valueOf(type) : null;
    }

    private UUID toUuid(String id) {
        return id != null ? UUID.fromString(id) : null;
    }


}