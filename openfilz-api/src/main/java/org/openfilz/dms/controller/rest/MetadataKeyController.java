package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.MetadataKeySuggestion;
import org.openfilz.dms.service.MetadataKeySuggestionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS)
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
public class MetadataKeyController {

    private final MetadataKeySuggestionService suggestionService;

    @GetMapping("/metadata-keys")
    @Operation(summary = "List metadata keys",
            description = "Metadata keys found on the documents the caller can see, most frequent first, with the number "
                    + "of documents carrying each (at most 200). Meant for autocompletion of the metadata search filter: "
                    + "the list is cached for a short time and is empty when it cannot be computed in time. "
                    + "404 when key suggestions are disabled.")
    public Mono<List<MetadataKeySuggestion>> listMetadataKeys() {
        if (!suggestionService.isEnabled()) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Metadata key suggestions are disabled"));
        }
        return suggestionService.getSuggestions();
    }
}
