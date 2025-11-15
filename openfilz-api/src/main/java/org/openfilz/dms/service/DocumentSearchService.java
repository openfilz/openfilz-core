package org.openfilz.dms.service;

import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import reactor.core.publisher.Mono;

import java.util.List;

public interface DocumentSearchService {

    Mono<DocumentSearchResult> search(String query, List<FilterInput> filters, SortInput sort, int page, int size, DataFetchingEnvironment environment);
}
