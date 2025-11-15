package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.repository.DocumentLocalSearchDAO;
import org.openfilz.dms.service.DocumentSuggestionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true)
public class DefaultDocumentSuggestionService implements DocumentSuggestionService {

    private final DocumentLocalSearchDAO searchDAO;

    /**
     * Provides search-as-you-type suggestions, returning a stream of Suggest objects.
     *
     * @param query The user's partial input string.
     * @return A Flux that emits Suggest objects, each containing a document ID and the suggestion text.
     */
    public Flux<Suggest> getSuggestions(String query, List<FilterInput> filters, SortInput sort) {
        if (query == null || query.isBlank()) {
            return Flux.empty();
        }

        return searchDAO.getSuggestions(query, filters, sort);
    }


}