package org.openfilz.dms.controller.graphql;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.service.DocumentSuggestionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
@RequestMapping("/api/v1/suggestions")
public class DocumentSuggestionController {

    private final DocumentSuggestionService suggestionService;


    @GetMapping
    public Mono<List<String>> getSuggestions(@RequestParam("q") String query) {
        // We only return a simple list of strings, which is ideal for autocomplete.
        return suggestionService.getSuggestions(query);
    }
}