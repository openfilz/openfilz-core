package org.openfilz.dms.service.impl;

import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchInfo;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.repository.graphql.AllFoldersDocumentQueryService;
import org.openfilz.dms.repository.graphql.DefaultDocumentQueryService;
import org.openfilz.dms.service.DocumentSearchService;
import org.openfilz.dms.utils.DocumentSearchUtil;
import org.openfilz.dms.utils.FileUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.openfilz.dms.utils.DocumentSearchUtil.FILTER_PARENT_ID;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true)
public class DefaultDocumentSearchService implements DocumentSearchService {

    private final AllFoldersDocumentQueryService allFoldersDocumentQueryService;
    private final DefaultDocumentQueryService documentQueryService;
    private final DocumentSearchUtil documentSearchUtil;

    @Override
    public Mono<DocumentSearchResult> search(String query, List<FilterInput> filters, SortInput sort, int page, int size, DataFetchingEnvironment environment) {
        Map<String, String> filterMap = documentSearchUtil.toFilterMap(filters);
        ListFolderRequest searchListFolderRequest = documentSearchUtil.toListFolderRequest(query, filterMap, sort, page, size);
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
                searchListFolderRequest.favorite(),
                searchListFolderRequest.active(),
                null
        );
        if(filterMap!=null && filterMap.containsKey(FILTER_PARENT_ID)) {
            return Mono.zip(
                    documentQueryService.count(countListFolderRequest, environment),
                    documentQueryService.findAll(searchListFolderRequest, environment).map(this::toDocumentSearchInfo).collectList()
            ).map(tuple -> new DocumentSearchResult(tuple.getT1(), tuple.getT2()));
        }
        return Mono.zip(
                allFoldersDocumentQueryService.count(countListFolderRequest, environment),
                allFoldersDocumentQueryService.findAll(searchListFolderRequest, environment).map(this::toDocumentSearchInfo).collectList()
        ).map(tuple -> new DocumentSearchResult(tuple.getT1(), tuple.getT2()));
    }

   private DocumentSearchInfo toDocumentSearchInfo(FullDocumentInfo fullDoc) {
        return new DocumentSearchInfo(
                fullDoc.id(),
                fullDoc.name(),
                FileUtils.getDocumentExtension(fullDoc.type(), fullDoc.name()),
                fullDoc.size(),
                fullDoc.parentId(),
                fullDoc.createdAt() != null ? fullDoc.createdAt().toString() : null,
                fullDoc.updatedAt() != null ? fullDoc.updatedAt().toString() : null,
                fullDoc.createdBy(),
                fullDoc.updatedBy()
        );
    }




}