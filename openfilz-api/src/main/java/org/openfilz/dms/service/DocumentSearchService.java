package org.openfilz.dms.service;

import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import reactor.core.publisher.Mono;

import java.util.List;

public interface DocumentSearchService {

    String FILTER_NAME = OpenSearchDocumentKey.name.toString();
    String FILTER_TYPE = "type";
    String FILTER_EXTENSION = OpenSearchDocumentKey.extension.toString();
    String FILTER_SIZE = OpenSearchDocumentKey.size.toString();
    String FILTER_PARENT_ID = OpenSearchDocumentKey.parentId.toString();
    String FILTER_CREATED_AT_BEFORE = "createdAtBefore";
    String FILTER_CREATED_AT_AFTER = "createdAtAfter";
    String FILTER_UPDATED_AT_BEFORE = "updatedAtBefore";
    String FILTER_UPDATED_AT_AFTER = "updatedAtAfter";
    String FILTER_CREATED_BY = OpenSearchDocumentKey.createdBy.toString();
    String FILTER_UPDATED_BY = OpenSearchDocumentKey.updatedBy.toString();
    String FILTER_METADATA = OpenSearchDocumentKey.metadata + ".";

    Mono<DocumentSearchResult> search(String query, List<FilterInput> filters, SortInput sort, int page, int size, DataFetchingEnvironment environment);
}
