package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.dto.signature.InstantiateTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateRequest;
import org.openfilz.dms.service.SignatureService;
import org.openfilz.dms.service.SignatureTemplateService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Reusable e-Sign templates (owner-scoped). Gated on {@code openfilz.signature.active} at runtime. */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SIGNATURE_TEMPLATES)
@RequiredArgsConstructor
@Tag(name = "e-Sign Templates", description = "Reusable roles + fields definitions for e-Sign envelopes.")
public class SignatureTemplateController implements UserInfoService {

    private final SignatureTemplateService service;
    private final SignatureProperties props;

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a template")
    public Mono<SignatureTemplateDTO> create(@Valid @RequestBody SignatureTemplateRequest req) {
        return email().flatMap(e -> service.create(req, e));
    }

    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a template")
    public Mono<SignatureTemplateDTO> update(@PathVariable UUID id, @Valid @RequestBody SignatureTemplateRequest req) {
        return email().flatMap(e -> service.update(id, req, e));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List my templates")
    public Flux<SignatureTemplateDTO> list() {
        return email().flatMapMany(service::list);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a template")
    public Mono<SignatureTemplateDTO> get(@PathVariable UUID id) {
        return email().flatMap(e -> service.get(id, e));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a template")
    public Mono<Void> delete(@PathVariable UUID id) {
        return email().flatMap(e -> service.delete(id, e));
    }

    @PostMapping(value = "/{id}/envelopes", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create (and send) an envelope from a template")
    public Mono<SignatureEnvelopeDTO> instantiate(@PathVariable UUID id, @Valid @RequestBody InstantiateTemplateRequest req) {
        requireActive();
        return getAuthenticationMono()
                .map(auth -> SignatureService.Actor.of(getUserAttribute(auth, "sub"), getUserAttribute(auth, "email")))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated")))
                .flatMap(a -> service.instantiate(id, req, a));
    }

    private Mono<String> email() {
        requireActive();
        return getConnectedUserEmail()
                .filter(e -> e != null && !ANONYMOUS_USER.equals(e))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated")));
    }

    private void requireActive() {
        if (!props.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "e-Sign feature is disabled");
        }
    }
}
