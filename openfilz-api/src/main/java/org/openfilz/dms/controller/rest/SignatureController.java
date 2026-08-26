package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.dto.signature.CloudSignatureSubscription;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureEventDTO;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.service.SignatureService;
import org.openfilz.dms.service.signature.CloudSubscriptionClient;
import org.openfilz.dms.service.signature.impl.CloudSignatureSealer;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Initiator-facing e-Sign endpoints. Always mapped; every call gates on
 * {@code openfilz.signature.active} at runtime (404 when off) so the toggle works in native
 * images. The signer-facing flow is in {@link PublicSignatureController}.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SIGNATURES)
@RequiredArgsConstructor
@Tag(name = "e-Sign", description = "Send PDFs for electronic signature and track envelopes.")
public class SignatureController implements UserInfoService {

    private final SignatureService service;
    private final SignatureProperties props;
    private final CloudSubscriptionClient cloudSubscriptionClient;

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create an envelope (sent immediately unless send=false)")
    public Mono<SignatureEnvelopeDTO> create(@Valid @RequestBody CreateSignatureEnvelopeRequest req) {
        return actor().flatMap(a -> service.create(req, a));
    }

    @PostMapping(value = "/{id}/send", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Send a DRAFT envelope")
    public Mono<SignatureEnvelopeDTO> send(@PathVariable UUID id) {
        return email().flatMap(e -> service.send(id, e));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List envelopes I sent for signature")
    public Flux<SignatureEnvelopeDTO> listSent(@RequestParam(required = false) SignatureEnvelopeStatus status) {
        return email().flatMapMany(e -> service.listSent(e, status));
    }

    @GetMapping(value = "/to-sign", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List envelopes waiting for my signature")
    public Flux<SignatureEnvelopeDTO> listToSign() {
        return email().flatMapMany(service::listToSign);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get one of my envelopes (recipients + fields)")
    public Mono<SignatureEnvelopeDTO> get(@PathVariable UUID id) {
        return email().flatMap(e -> service.get(id, e));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Audit trail of one of my envelopes")
    public Flux<SignatureEventDTO> events(@PathVariable UUID id) {
        return email().flatMapMany(e -> service.events(id, e));
    }

    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel a non-terminal envelope")
    public Mono<SignatureEnvelopeDTO> cancel(@PathVariable UUID id) {
        return email().flatMap(e -> service.cancel(id, e));
    }

    @PostMapping(value = "/{id}/recipients/{recipientId}/resend", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Resend the signing link to a recipient (new token, previous one revoked)")
    public Mono<SignatureEnvelopeDTO> resend(@PathVariable UUID id, @PathVariable UUID recipientId) {
        return email().flatMap(e -> service.resend(id, recipientId, e));
    }

    @GetMapping(value = "/cloud-subscription", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cloud Signing subscription and month-to-date usage (openfilz-cloud seal provider only)")
    public Mono<CloudSignatureSubscription> cloudSubscription() {
        requireCloudSealing();
        return email().then(Mono.defer(cloudSubscriptionClient::fetch));
    }

    @GetMapping(value = "/{id}/signed-document", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Download the sealed PDF of a completed envelope")
    public Mono<ResponseEntity<Resource>> signedDocument(@PathVariable UUID id) {
        return email().flatMap(e -> service.loadSignedDocument(id, e))
                .map(res -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"signed.pdf\"")
                        .body(res));
    }

    // ─────────────────────────────────────────────────────────────────────

    private Mono<SignatureService.Actor> actor() {
        requireActive();
        return getAuthenticationMono()
                .map(auth -> SignatureService.Actor.of(getUserAttribute(auth, "sub"), emailOf(auth)))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated")));
    }

    private Mono<String> email() {
        requireActive();
        return getConnectedUserEmail()
                .filter(e -> e != null && !ANONYMOUS_USER.equals(e))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated")));
    }

    private String emailOf(Authentication auth) {
        String email = getUserAttribute(auth, "email");
        if (email == null || email.isBlank() || ANONYMOUS_USER.equals(email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The token has no email claim");
        }
        return email;
    }

    private void requireActive() {
        if (!props.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "e-Sign feature is disabled");
        }
    }

    private void requireCloudSealing() {
        requireActive();
        SignatureProperties.Seal.Cloud cloud = props.getSeal().getCloud();
        if (!CloudSignatureSealer.ID.equals(props.getSeal().getProvider())
                || cloud.getApiKey() == null || cloud.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cloud Signing is not enabled");
        }
    }
}
