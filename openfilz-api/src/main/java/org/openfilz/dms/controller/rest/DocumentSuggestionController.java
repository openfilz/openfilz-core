package org.openfilz.dms.controller.rest;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.service.DocumentSuggestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(RestApiVersion.API_PREFIX + "/suggestions")
public class DocumentSuggestionController {

    private final DocumentSuggestionService suggestionService;


    @GetMapping
    public Flux<Suggest> getSuggestions(@RequestParam("q") String query, @RequestParam(name = "f", required = false) List<FilterInput> filters, @RequestParam(name = "s", required = false) SortInput sort) {
        return suggestionService.getSuggestions(query, filters, sort);
    }
}