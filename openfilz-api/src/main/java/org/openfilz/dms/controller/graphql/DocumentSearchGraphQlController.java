package org.openfilz.dms.controller.graphql;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.service.DocumentSearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.util.List;


@Controller
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class DocumentSearchGraphQlController {

    private final DocumentSearchService documentSearchService; // You can reuse the service logic

    @QueryMapping
    public Mono<DocumentSearchResult> searchDocuments(
            @Argument String query,
            @Argument List<FilterInput> filters,
            @Argument SortInput sort,
            @Argument int page,
            @Argument int size) {
        
        // The service method would be adapted to take these typed inputs
        // instead of a raw MultiValueMap. This is much cleaner.
        return documentSearchService.search(query, filters, sort, page, size);
    }
}