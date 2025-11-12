package org.openfilz.dms.controller.graphql;

import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.service.DocumentSearchService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.util.List;


@Controller
@RequiredArgsConstructor
public class DocumentSearchGraphQlController {

    private final DocumentSearchService documentSearchService;

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
}