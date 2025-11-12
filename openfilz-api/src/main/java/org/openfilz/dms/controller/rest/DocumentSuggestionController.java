package org.openfilz.dms.controller.rest;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.service.DocumentSuggestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@RequestMapping(RestApiVersion.API_PREFIX + "/suggestions")
public class DocumentSuggestionController {

    private final DocumentSuggestionService suggestionService;


    @GetMapping
    public Flux<Suggest> getSuggestions(@RequestParam("q") String query) {
        return suggestionService.getSuggestions(query);
    }
}