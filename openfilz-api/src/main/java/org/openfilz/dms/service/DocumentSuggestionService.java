package org.openfilz.dms.service;

import reactor.core.publisher.Mono;

import java.util.List;

public interface DocumentSuggestionService {
    Mono<List<String>> getSuggestions(String query);
}
