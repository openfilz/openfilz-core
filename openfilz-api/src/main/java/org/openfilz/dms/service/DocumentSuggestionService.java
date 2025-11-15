package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.Suggest;
import reactor.core.publisher.Flux;

import java.util.List;

public interface DocumentSuggestionService {
    Flux<Suggest> getSuggestions(String query, List<FilterInput> filters, SortInput sort);
}
